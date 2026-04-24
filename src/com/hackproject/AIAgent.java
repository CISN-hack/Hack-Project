package com.hackproject;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.StringReader;

public class AIAgent {

    private static final String API_KEY   = "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";
    private static final String MODEL_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String MODEL_ID  = "meta/llama-3.3-70b-instruct";

    // Fix 2: single shared HttpClient — avoids spinning up a new thread pool per API call
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // Fix 3: load tools.json once at startup — it never changes at runtime
    private static final JsonArray TOOLS_CACHE = loadToolsJson();
    private static JsonArray loadToolsJson() {
        try {
            return JsonParser.parseString(Files.readString(Paths.get("tools.json"))).getAsJsonArray();
        } catch (Exception e) {
            System.out.println("CRITICAL: tools.json not found!");
            return new JsonArray();
        }
    }

    private static final String SYSTEM_INSTRUCTION =
        "You are Zai, a Warehouse System of Intelligence.\n\n" +

        "PROTOCOL OMEGA (EMERGENCY — overrides everything else):\n" +
        "  • If the worker reports a broken machine, spill, blocked aisle, fire, injury, or any accident, IMMEDIATELY call 'reportAccident' with the location and description.\n" +
        "  • After the tool returns, reassure the worker: management has been notified and routing has been recalculated to avoid the area.\n" +
        "  • Do NOT continue any delivery workflow while an emergency is being reported.\n\n" +

        "PROTOCOL CLEAR (AISLE REOPENING):\n" +
        "  • If the worker or manager says an aisle or area is now safe, clear, or back to normal, IMMEDIATELY call 'clearAisle' with the location.\n" +
        "  • After the tool returns, confirm to the worker that the area is unblocked and routing has been restored.\n" +
        "  • Do NOT continue any delivery workflow until you have called 'clearAisle'.\n\n" +

        "PROTOCOL QUERY (INVENTORY LOOKUP — NOT a delivery):\n" +
        "  • SINGLE PRODUCT — if the worker asks about ONE product (e.g. 'is there any K15VC?', 'do we have ceiling fans?', 'where is K15VC?'):\n" +
        "      – Resolve the product ID first — use 'searchProductByDescription' if the worker gave a name instead of an ID.\n" +
        "      – Call 'findProductLocation' with the product ID.\n" +
        "      – Reply in plain language based on the tool's result:\n" +
        "          · 0 bins found → 'No, there is no [product name] in the warehouse right now.'\n" +
        "          · 1+ bins found → 'Yes — there are [total units] unit(s) of [product name] in [N] bin(s): [bin list].'\n" +
        "          · The tool result includes the exact unit count — always report it. Never say '0 units' if the tool found bins.\n" +
        "  • WHOLE WAREHOUSE — if the worker asks for the FULL inventory (e.g. 'what products do we have?', 'list everything in stock', 'show warehouse inventory'):\n" +
        "      – Call 'listWarehouseInventory' (no arguments).\n" +
        "      – Present the tool result directly to the worker (it is already formatted as a list).\n" +
        "  • DO NOT call readLocalCustomerOrder, findOptimalBin, or updateBinStatus. Lookups are read-only.\n" +
        "  • DO NOT start the delivery workflow.\n\n" +

        "DELIVERY WORKFLOW (follow every step in order, no skipping):\n\n" +

        "STEP 1 — IDENTIFY THE PRODUCT:\n" +
        "  • Only enter this step if the worker's message clearly describes a stock arrival or product lookup (e.g. mentions a product name, product ID, lorry, shipment, delivery, or asks where something is). If the message is ambiguous or unrelated to warehouse operations, ask the worker to clarify what they need — do NOT call any tool.\n" +
        "  • A deterministic pre-screen runs before you see the message: if the worker said only that stock/a lorry/a delivery arrived with NO product specified, Java already replied asking for the product — you will receive the product name or ID in the next message.\n" +
        "  • If the worker gave a product ID (e.g. 'K15VC'), call 'getProductAnalysis' with that ID.\n" +
        "  • If the worker described a product by name (e.g. 'ceiling fan'), call 'searchProductByDescription' first.\n" +
        "  • If 'searchProductByDescription' returns EXACTLY ONE product, show the result to the worker and ask them to confirm it is the correct product BEFORE calling 'getProductAnalysis'. Do NOT auto-proceed.\n" +
        "  • If 'searchProductByDescription' returns MORE THAN ONE product, list all results to the worker and ask which one arrived. STOP — do NOT call 'getProductAnalysis' until the worker picks one.\n" +
        "  • Do NOT proceed until you have a confirmed product ID.\n\n" +

        "STEP 1b — GET ARRIVING QUANTITY (HARD GATE):\n" +
        "  • Ask the worker: 'How many units of [product name] arrived?'\n" +
        "  • Do NOT proceed until the worker gives you a number.\n" +
        "  • Store this number — you will need it to decide routing in STEP 2.\n" +
        "  • If the worker asks where to put stock / for the optimal bin / for the exact location but has NOT given a quantity, you MUST reply with ONLY the quantity question. Do not call any tool, do not suggest a bin, do not mention the Packing Counter, do not guess a number like 1. Quantity is REQUIRED before any routing.\n\n" +

        "STEP 2 — CHECK THE CUSTOMER ORDER:\n" +
        "  • Call 'readLocalCustomerOrder'.\n" +
        "  • A [CO MATCH FOUND] or [NO CO MATCH] message will be injected automatically after the result.\n" +
        "  • Read it carefully and follow its routing instruction exactly.\n\n" +

        "STEP 3 — BINNING (only if needed):\n" +
        "  • Use the product analysis from Step 1 to calculate weight/volume for the units being binned.\n" +
        "  • Call 'findOptimalBin' with those values.\n\n" +

        "STEP 4 — TELL THE WORKER (CRITICAL — DO NOT SKIP):\n" +
        "  • After 'findOptimalBin' returns, STOP calling tools immediately.\n" +
        "  • Reply with a TEXT MESSAGE ONLY (no tool calls).\n" +
        "  • If units go to the Packing Counter, state that first.\n" +
        "  • For bin placements, ALWAYS use this exact list format — one line per bin, no prose sentences:\n" +
        "      Bin <bin_id>: place <N> units.\n" +
        "      Bin <bin_id>: place <N> units.\n" +
        "      (repeat for every bin)\n" +
        "  • End with: 'Reply \"done\" when you have placed the items.'\n" +
        "  • STOP. Do not call any tool until the worker replies with 'done'.\n" +
        "  • HARD RULE: you may ONLY mention a specific bin ID, a unit split, or the Packing Counter if 'findOptimalBin' (or an injected [CO MATCH FOUND] / [SYSTEM PACKING ONLY] message) has already produced that routing in this conversation. NEVER invent a bin ID or unit count. If quantity is missing, go back to STEP 1b and ask for it.\n\n" +

        "STEP 5 — COMMIT (only after worker confirms):\n" +
        "  • Only if the worker's latest message is 'done', 'placed', 'confirmed', or similar, call 'updateBinStatus'.\n" +
        "  • NEVER call updateBinStatus right after findOptimalBin — it WILL be rejected.\n\n" +

        "Refuse new tasks until the current one is confirmed.\n\n" +

        "OUT OF SCOPE:\n" +
        "  • If the worker asks anything unrelated to warehouse operations (directions, personal questions, general knowledge, etc.), respond ONLY with plain text:\n" +
        "    \"I can only assist with warehouse tasks such as deliveries, inventory lookups, and emergencies.\"\n" +
        "  • Do NOT call any tool for out-of-scope questions.";

    private static final String TOOL_ERROR_PREFIX = "TOOL_DISPATCH_ERROR:";
    private static final JsonArray conversationHistory = new JsonArray();

    static {
        JsonObject welcome = new JsonObject();
        welcome.addProperty("role", "assistant");
        welcome.addProperty("content", "Welcome to Zai Warehouse Intelligence. I am online and ready to assist. Has a delivery arrived, or do you have an inventory query?");
        conversationHistory.add(welcome);
    }

    private static String lastArrivingProductId   = "";
    private static String lastArrivingProductName = "";
    private static String lastPoCustomer          = "";
    private static int    lastArrivingQuantity    = -1;
    private static boolean awaitingArrivalProduct = false;
    private static String pendingBareProductId    = "";
    private static boolean poCheckDone            = false;
    private static boolean allToPackingCounter    = false;
    private static int     remainingQtyToBin      = 0;
    private static final List<String> tentativeBins = new ArrayList<>();
    private static final java.util.Map<String, Integer> tentativeBinQtys = new java.util.HashMap<>();
    private static int     pendingUpdateCount     = 0;
    // True whenever Zai has just asked the worker for an arriving quantity and is waiting on a reply.
    // Set by the QUANTITY_MISSING handler and the routing-hallucination guard; cleared once the worker
    // answers with a number, at which point we inject a [SYSTEM QUANTITY RECEIVED] nudge to resume
    // the delivery workflow instead of relying on the LLM to remember where it left off.
    private static boolean awaitingQuantityFromWorker = false;

    // ── Shift log ─────────────────────────────────────────────────────────────
    private static final long         shiftStartTime = System.currentTimeMillis();
    private static final List<String> deliveryLog    = new ArrayList<>();
    private static final List<String> accidentLog    = new ArrayList<>();
    private static final List<String> clearedLog     = new ArrayList<>();

    private static String now() {
        return java.time.LocalTime.now()
               .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static final Pattern STANDALONE_NUMBER = Pattern.compile("\\b(\\d{1,6})\\b");
    private static final Pattern EMERGENCY_KEYWORDS = Pattern.compile(
        "\\b(accident|accidental|broke|broken|fire|smoke|injury|injured|hurt|pain|spill|leak|block|blocked|aisle|machine|forklift|fall|fell|fallen|emergency|danger|dangerous|collapse|stuck|trapped|flood|damage|damaged|crash|crashed|explosion|explode)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ARRIVAL_SIGNAL = Pattern.compile(
        "\\b(lorry|lorries|truck|trucks|deliver(?:y|ies|ed)|shipment|arrived|arrival|cargo|received|incoming|unloaded|dispatched)\\b",
        Pattern.CASE_INSENSITIVE);
    // Matches tokens that look like a product code (letters + at least one digit), e.g. K15VC, PHL-9W-D
    private static final Pattern PRODUCT_CODE_IN_MSG = Pattern.compile("\\b[A-Za-z]{1,4}\\d[A-Za-z0-9-]*\\b");

    // Extract a bare integer from the worker's message (e.g. "a lorry of 50 K15VC" -> 50).
    // Word-boundary anchors mean embedded digits like "15" inside "K15VC" are ignored.
    private static void tryUpdateArrivingQuantity(String userInput) {
        Matcher m = STANDALONE_NUMBER.matcher(userInput);
        while (m.find()) {
            try {
                int q = Integer.parseInt(m.group(1));
                if (q > 0 && q < 100000) { lastArrivingQuantity = q; return; }
            } catch (NumberFormatException ignored) {}
        }
    }

    private static JsonArray getToolsJson() {
        return TOOLS_CACHE;
    }

    // Deterministic CO matching done in Java so the model can't miss or misread a hit.
    // Injected as a user-role message after readLocalCustomerOrder so the model sees explicit routing.
    private static String buildPoMatchMessage(String csvData, String arrivingProductId) {
        if (arrivingProductId == null || arrivingProductId.isBlank()) {
            return "[CO MATCH SKIPPED] No arriving product ID was recorded. Ask the worker for the product ID.";
        }
        // Fix 7: stream line-by-line instead of splitting the entire file into a String array
        try (BufferedReader reader = new BufferedReader(new StringReader(csvData))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return "[NO CO MATCH] CO file is empty or unreadable. Bin all arriving units.";
            }

            String[] headers = headerLine.split(",");
            int colCustomer  = -1;
            int colProductId = -1;
            int colQty       = -1;
            int colPriority  = -1;

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if (h.equals("customername") || h.equals("customer_name") || h.equals("customer"))
                    colCustomer = i;
                else if (h.equals("productid") || h.equals("product_id") || h.equals("product"))
                    colProductId = i;
                else if (h.equals("quantity") || h.equals("qty"))
                    colQty = i;
                else if (h.equals("priority"))
                    colPriority = i;
            }

            if (colProductId == -1) {
                return "[CO MATCH ERROR] Could not find ProductID column in CO file. Bin all arriving units.";
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",");
                if (cols.length <= colProductId) continue;

                String poProductId = cols[colProductId].trim();
                if (poProductId.equalsIgnoreCase(arrivingProductId.trim())) {
                    String customer = (colCustomer >= 0 && colCustomer < cols.length)
                            ? cols[colCustomer].trim() : "Unknown Customer";
                    String qty = (colQty >= 0 && colQty < cols.length)
                            ? cols[colQty].trim() : "?";
                    String priority = (colPriority >= 0 && colPriority < cols.length)
                            ? cols[colPriority].trim() : "Standard";

                    return "[CO MATCH FOUND]\n" +
                           "Product : " + poProductId + "\n" +
                           "Customer: " + customer + "\n" +
                           "CO Qty  : " + qty + " units\n" +
                           "Priority: " + priority + "\n\n" +
                           "ROUTING INSTRUCTION:\n" +
                           "Route " + qty + " units to the Packing Counter for " + customer +
                           " (" + priority + " priority).\n" +
                           "If arriving qty > " + qty + ", bin the remainder using getProductAnalysis + findOptimalBin.\n" +
                           "If arriving qty <= " + qty + ", send ALL to Packing Counter — do NOT bin anything.";
                }
            }
        } catch (java.io.IOException ignored) {}

        return "[NO CO MATCH] Product '" + arrivingProductId + "' is not in the current CO.\n" +
               "ROUTING INSTRUCTION: Bin all arriving units using getProductAnalysis + findOptimalBin.";
    }

    public static String callAPI(JsonArray history, boolean allowTools) {
        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", SYSTEM_INSTRUCTION);
        messages.add(sysMsg);
        messages.addAll(history);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", MODEL_ID);
        payload.add("messages", messages);
        if (allowTools) {
            payload.add("tools", getToolsJson());
            payload.addProperty("tool_choice", "auto");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        int maxRetries = 3;
        long waitMs = 5000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> resp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                String body = resp.body();
                if (resp.statusCode() == 429 || body.contains("\"status\":429")) {
                    System.out.println("[RATE LIMIT] Attempt " + attempt + "/3 — waiting " + (waitMs / 1000) + "s...");
                    if (attempt < maxRetries) {
                        Thread.sleep(waitMs);
                        waitMs *= 2;
                        continue;
                    }
                    return "{\"error\":{\"message\":\"Rate limit exceeded.\"}}";
                }
                return body;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "{\"error\":{\"message\":\"Interrupted.\"}}";
            } catch (Exception e) {
                return "{\"error\":{\"message\":\"Network error: " + e.getMessage() + "\"}}";
            }
        }
        return "{\"error\":{\"message\":\"All retries failed.\"}}";
    }

    public static String callAPI(JsonArray history) {
        return callAPI(history, true);
    }

    public static void processAIResponse(String jsonResponse, int depth) {
        final int MAX_DEPTH = 8;

        JsonObject root;
        try {
            root = JsonParser.parseString(jsonResponse).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("[ERROR] Non-JSON response: " + jsonResponse);
            return;
        }

        if (root.has("error")) {
            JsonElement err = root.get("error");
            String msg = err.isJsonObject() && err.getAsJsonObject().has("message")
                    ? err.getAsJsonObject().get("message").getAsString()
                    : err.toString();
            System.out.println("[API ERROR] " + msg);
            return;
        }

        JsonObject message;
        try {
            message = root.getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message");
        } catch (Exception e) {
            System.out.println("[ERROR] Unexpected response structure: " + jsonResponse);
            return;
        }

        if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {

            if (depth >= MAX_DEPTH) {
                System.out.println("[WARNING] Max depth reached. Forcing text reply.");
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                processAIResponse(callAPI(conversationHistory, false), depth);
                return;
            }

            JsonArray toolCalls = message.getAsJsonArray("tool_calls");

            // Empty tool_calls array: nothing to do. Fall through to text branch so we don't
            // recurse with tools re-enabled and trigger a retry storm.
            if (toolCalls.size() == 0) {
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    String content = message.get("content").getAsString();
                    if (!content.isBlank()) System.out.println("\nZai: " + content);
                }
                conversationHistory.add(message);
                return;
            }

            conversationHistory.add(message);

            boolean hadError = false;
            boolean updateBinStatusBlocked = false;
            boolean updateBinSucceeded = false;
            boolean accidentReported = false;
            boolean aisleCleared = false;
            boolean quantityMissing = false;
            boolean coCheckRequired = false;

            for (JsonElement toolEl : toolCalls) {
                JsonObject toolCallObj = toolEl.getAsJsonObject();

                if (!toolCallObj.has("id") || !toolCallObj.has("function")) {
                    hadError = true;
                    continue;
                }

                String toolCallId = toolCallObj.get("id").getAsString();
                JsonObject function = toolCallObj.getAsJsonObject("function");
                String funcName = function.has("name") ? function.get("name").getAsString() : "";

                JsonObject args;
                try {
                    args = JsonParser.parseString(function.get("arguments").getAsString()).getAsJsonObject();
                } catch (Exception e) {
                    args = new JsonObject();
                }

                System.out.println("\n[SYSTEM] Zai activating skill: " + funcName);
                String toolOutput = dispatchTool(funcName, args);
                System.out.println("[DATA RETRIEVED]\n" + toolOutput);

                if (toolOutput.startsWith(TOOL_ERROR_PREFIX)) {
                    hadError = true;
                    if ("updateBinStatus".equals(funcName) && toolOutput.contains("BLOCKED")) {
                        updateBinStatusBlocked = true;
                    }
                    if (toolOutput.contains("QUANTITY_MISSING")) {
                        quantityMissing = true;
                    }
                    if (toolOutput.contains("CO_CHECK_REQUIRED")) {
                        coCheckRequired = true;
                    }
                } else if ("updateBinStatus".equals(funcName)) {
                    updateBinSucceeded = true;
                } else if ("reportAccident".equals(funcName)) {
                    accidentReported = true;
                } else if ("clearAisle".equals(funcName)) {
                    aisleCleared = true;
                }

                JsonObject toolResultMsg = new JsonObject();
                toolResultMsg.addProperty("role", "tool");
                toolResultMsg.addProperty("tool_call_id", toolCallId);
                toolResultMsg.addProperty("content", toolOutput);
                conversationHistory.add(toolResultMsg);

                if ("readLocalCustomerOrder".equals(funcName)) {
                    String matchResult = buildPoMatchMessage(toolOutput, lastArrivingProductId);
                    System.out.println("\n[SYSTEM CO CHECK]\n" + matchResult);

                    JsonObject poCheckMsg = new JsonObject();
                    poCheckMsg.addProperty("role", "user");
                    poCheckMsg.addProperty("content",
                        "[SYSTEM CO CHECK — READ THIS BEFORE DOING ANYTHING ELSE]\n" + matchResult);
                    conversationHistory.add(poCheckMsg);

                    // Notify managers via Telegram when arriving stock matches a CO
                    if (matchResult.startsWith("[CO MATCH FOUND]")) {
                        String customer = "Unknown";
                        String coQty    = "?";
                        String priority = "Standard";
                        for (String line : matchResult.split("\\r?\\n")) {
                            if (line.startsWith("Customer:")) customer = line.substring("Customer:".length()).trim();
                            else if (line.startsWith("CO Qty  :")) coQty = line.substring("CO Qty  :".length()).trim();
                            else if (line.startsWith("Priority:")) priority = line.substring("Priority:".length()).trim();
                        }
                        WarehouseSkills.notifyCoArrival(lastArrivingProductId, customer, coQty, priority, lastArrivingQuantity);
                        lastPoCustomer = customer;

                        // Detect "all to Packing Counter" vs split-delivery case
                        try {
                            int coQtyNum = Integer.parseInt(coQty.replaceAll("[^0-9]", ""));
                            if (lastArrivingQuantity <= coQtyNum) {
                                allToPackingCounter = true;
                                JsonObject packMsg = new JsonObject();
                                packMsg.addProperty("role", "user");
                                packMsg.addProperty("content",
                                    "[SYSTEM PACKING ONLY] All " + lastArrivingQuantity + " units go to the Packing Counter for "
                                    + customer + ". Do NOT call findOptimalBin or updateBinStatus. "
                                    + "Tell the worker to take all units to the Packing Counter and reply 'done'. No further tool calls are needed.");
                                conversationHistory.add(packMsg);
                            } else {
                                int binQty = lastArrivingQuantity - coQtyNum;
                                remainingQtyToBin = binQty;
                                JsonObject splitMsg = new JsonObject();
                                splitMsg.addProperty("role", "user");
                                splitMsg.addProperty("content",
                                    "[SYSTEM SPLIT DELIVERY] Send exactly " + coQtyNum + " units to the Packing Counter for "
                                    + customer + " (" + priority + " priority). "
                                    + "Bin the remaining " + binQty + " units — call findOptimalBin now to get the bin locations. "
                                    + "Do NOT bin more than " + binQty + " units.");
                                conversationHistory.add(splitMsg);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Model skipped the CO check — re-enable tools and inject a corrective nudge so it
            // calls readLocalCustomerOrder instead of outputting raw JSON in text-only mode.
            if (coCheckRequired) {
                JsonObject nudge = new JsonObject();
                nudge.addProperty("role", "user");
                nudge.addProperty("content",
                    "[SYSTEM CO CHECK REQUIRED] You skipped STEP 2. You MUST call 'readLocalCustomerOrder' NOW before doing anything else. Do not call findOptimalBin again until after the CO check.");
                conversationHistory.add(nudge);
                System.out.println("[Zai is correcting workflow — running CO check...]");
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                processAIResponse(callAPI(conversationHistory), depth + 1);
                return;
            }

            // Gate: the CO check can't run without a known arrival quantity. Ask the worker
            // directly rather than letting the model hallucinate a number.
            if (quantityMissing) {
                String productLabel = lastArrivingProductId.isEmpty() ? "the product" : lastArrivingProductId;
                String reply = "How many units of " + productLabel + " arrived?";
                System.out.println("\nZai: " + reply);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                awaitingQuantityFromWorker = true;
                return;
            }

            // reportAccident is a terminal one-shot action. Break the chain so the model
            // doesn't redundantly re-call it on the next turn.
            if (accidentReported) {
                String reply = "Management has been notified and routing has been recalculated to avoid the area. Stay safe.";
                System.out.println("\nZai: " + reply);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                return;
            }

            // clearAisle is also a terminal one-shot action.
            if (aisleCleared) {
                String reply = "The area has been unblocked and routing has been restored. Workers can now access the aisle.";
                System.out.println("\nZai: " + reply);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                return;
            }

            // If updateBinStatus was blocked, the model jumped ahead. Stop the API chain and
            // emit a direct text reply so the worker can respond with 'done' to continue.
            if (updateBinStatusBlocked) {
                String binId = findLastBinId();
                String reply = binId != null
                        ? "Please place the items in bin " + binId + " and reply 'done' when you have placed them."
                        : "Please place the items and reply 'done' when you have placed them.";
                System.out.println("\nZai: " + reply);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                return;
            }

            // Java-driven multi-bin commit: the LLM committed the first bin; Java commits
            // the rest directly without further API calls to avoid rate limit hits.
            if (updateBinSucceeded && !tentativeBins.isEmpty()) {
                String productId   = lastArrivingProductId;
                String productName = lastArrivingProductName;
                List<String> remaining = new ArrayList<>(tentativeBins);
                StringBuilder summary = new StringBuilder(
                    "[SYSTEM MULTI-BIN COMMIT] Java committed remaining "
                    + remaining.size() + " bin(s) directly:\n");
                for (String bin : remaining) {
                    int binQty = tentativeBinQtys.getOrDefault(bin, 0);
                    String result = InventoryTools.updateBinStatus(bin, "auto", productId, binQty);
                    System.out.println("[SYSTEM] Direct commit: " + result);
                    summary.append("- ").append(bin).append(": ").append(result).append("\n");
                    if (!result.startsWith("SYSTEM ERROR") && !result.startsWith("Database")
                            && !result.equals("Update failed.") && !result.equals("Bin not found in database.")) {
                        String logName = productName.isEmpty()
                            ? productId : productName + " (" + productId + ")";
                        deliveryLog.add("[" + now() + "] " + logName + " → Bin " + bin);
                    }
                    tentativeBins.remove(bin);
                    tentativeBinQtys.remove(bin);
                    pendingUpdateCount = Math.max(0, pendingUpdateCount - 1);
                }
                if (pendingUpdateCount <= 0) {
                    lastArrivingProductId      = "";
                    lastArrivingProductName    = "";
                    lastPoCustomer             = "";
                    lastArrivingQuantity       = -1;
                    poCheckDone                = false;
                    allToPackingCounter        = false;
                    remainingQtyToBin          = 0;
                    tentativeBins.clear(); tentativeBinQtys.clear();
                    awaitingArrivalProduct     = false;
                    pendingBareProductId       = "";
                    awaitingQuantityFromWorker = false;
                }
                // Fix 8: one batch UPDATE for all committed bins instead of N individual queries
                InventoryTools.refreshCapacityPct(remaining);
                JsonObject summaryMsg = new JsonObject();
                summaryMsg.addProperty("role", "user");
                summaryMsg.addProperty("content", summary.toString().trim());
                conversationHistory.add(summaryMsg);
                processAIResponse(callAPI(conversationHistory, false), depth + 1);
                return;
            }

            if (hadError) {
                System.out.println("[Zai is recovering...]");
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                processAIResponse(callAPI(conversationHistory, false), depth);
                return;
            }

            System.out.println("[Zai is analyzing the new data...]");
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            processAIResponse(callAPI(conversationHistory), depth + 1);

        } else if (message.has("content") && !message.get("content").isJsonNull()) {
            String content = message.get("content").getAsString();

            // Hallucination guard: the model sometimes replies with routing instructions
            // (bin IDs, "N units to the Packing Counter") without ever calling findOptimalBin,
            // inventing a quantity like 1. If we have no confirmed arriving quantity yet,
            // overwrite the reply with the quantity question instead of emitting garbage.
            if (lastArrivingQuantity <= 0 && looksLikeRoutingInstruction(content)) {
                String productLabel = lastArrivingProductId.isEmpty() ? "the product" : lastArrivingProductId;
                String safe = "How many units of " + productLabel + " arrived? I need the quantity before I can find a bin.";
                System.out.println("\nZai: " + safe);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", safe);
                conversationHistory.add(aMsg);
                awaitingQuantityFromWorker = true;
                return;
            }

            System.out.println("\nZai: " + content);
            conversationHistory.add(message);
        }
    }

    private static String dispatchTool(String funcName, JsonObject args) {
        try {
            switch (funcName) {
                case "readLocalCustomerOrder":
                    if (lastArrivingQuantity <= 0) {
                        return TOOL_ERROR_PREFIX +
                               " QUANTITY_MISSING. Do not check the CO yet. Ask the worker how many units arrived first.";
                    }
                    poCheckDone = true;
                    return LocalFileTools.readLocalCustomerOrder(
                            args.has("filePath") ? args.get("filePath").getAsString() : "CO_April20.csv");

                case "getProductAnalysis":
                    lastArrivingProductId = args.get("productId").getAsString().toUpperCase();
                    System.out.println("[SYSTEM] AI resolved product ID: " + lastArrivingProductId);
                    String pa = WarehouseSkills.getProductAnalysis(args.get("productId").getAsString());
                    if (pa.startsWith("Product: ")) {
                        int c = pa.indexOf(",");
                        if (c > 9) lastArrivingProductName = pa.substring(9, c).trim();
                    }
                    return pa;

                case "findOptimalBin":
                    if (allToPackingCounter) {
                        return TOOL_ERROR_PREFIX +
                               " PACKING_COUNTER_ONLY. All arriving units are routed to the Packing Counter. " +
                               "Do NOT call findOptimalBin. Tell the worker to take all units to the Packing Counter " +
                               "and reply 'done'. No binning is needed.";
                    }
                    if (lastArrivingQuantity <= 0) {
                        return TOOL_ERROR_PREFIX +
                               " QUANTITY_MISSING. Do not find a bin yet. Ask the worker how many units arrived first.";
                    }
                    if (lastArrivingProductId.isEmpty()) {
                        return TOOL_ERROR_PREFIX +
                               " PRODUCT_MISSING. Call getProductAnalysis (or searchProductByDescription) first.";
                    }
                    if (!poCheckDone) {
                        return TOOL_ERROR_PREFIX +
                               " CO_CHECK_REQUIRED. Call readLocalCustomerOrder before finding a bin.";
                    }
                    // Java computes weight/volume/velocity itself from the DB so the model can't
                    // mis-multiply or echo stale numbers. The args passed in are ignored.
                    double[] dims = WarehouseSkills.getProductDimensions(lastArrivingProductId);
                    if (dims == null) {
                        return TOOL_ERROR_PREFIX + " Product '" + lastArrivingProductId + "' not found in database.";
                    }
                    double unitWeight = dims[0];
                    double unitVolume = dims[1];
                    int    velocity   = (int) Math.round(dims[2]);
                    if (remainingQtyToBin <= 0) remainingQtyToBin = lastArrivingQuantity;
                    System.out.println("[SYSTEM] per-unit: " + lastArrivingProductId
                            + " => " + unitWeight + "kg, " + unitVolume + "m3, velocity=" + velocity
                            + ", total qty=" + lastArrivingQuantity);
                    // Loop until all units are assigned or the warehouse runs out of space.
                    StringBuilder routingPlan = new StringBuilder();
                    int binsFound = 0;
                    while (remainingQtyToBin > 0) {
                        String binRaw = WarehouseSkills.findOptimalBin(unitWeight, unitVolume, velocity, lastArrivingProductId, remainingQtyToBin, tentativeBins);
                        if (!binRaw.startsWith("BINASSIGN|")) {
                            if (binsFound == 0) return binRaw; // no bins at all
                            routingPlan.append(remainingQtyToBin)
                                       .append(" units could not be binned — warehouse at capacity.");
                            break;
                        }
                        String[] parts    = binRaw.split("\\|");
                        String assignedBin = parts[1];
                        int    unitsInBin  = Integer.parseInt(parts[2]);
                        remainingQtyToBin  = Integer.parseInt(parts[3]);
                        tentativeBins.add(assignedBin);
                        tentativeBinQtys.put(assignedBin, unitsInBin);
                        binsFound++;
                        routingPlan.append("Bin ").append(assignedBin)
                                   .append(": place ").append(unitsInBin).append(" units.\n");
                    }
                    pendingUpdateCount = binsFound;
                    if (binsFound == 1) {
                        // Single bin — return compact format the model is used to
                        return routingPlan.toString().trim();
                    }
                    return "ROUTING PLAN — " + binsFound + " bins needed:\n" + routingPlan.toString().trim()
                         + "\nTell the worker all bin locations at once, then call updateBinStatus for each bin after they confirm 'done'.";

                case "listWarehouseInventory":
                    return WarehouseSkills.listWarehouseInventory();

                case "findProductLocation":
                    return WarehouseSkills.findProductLocation(
                            args.get("productId").getAsString().toUpperCase());

                case "searchProductByDescription":
                    String searchResult = WarehouseSkills.searchProductByDescription(args.get("keyword").getAsString());
                    if (searchResult.startsWith("No products found")) {
                        return "No products found matching that description. "
                             + "STOP — do NOT call listWarehouseInventory or any other tool. "
                             + "Tell the worker: \"I couldn't find a product matching that description. "
                             + "Please check the product name or ID and try again.\"";
                    }
                    Matcher m = Pattern
                        .compile("(?i)(?:product[:\\s]+|id[:\\s]+)([A-Z0-9][A-Z0-9\\-]{1,14})")
                        .matcher(searchResult);
                    String firstId = null;
                    int matchCount = 0;
                    while (m.find()) {
                        matchCount++;
                        if (firstId == null) firstId = m.group(1).toUpperCase();
                    }
                    if (matchCount == 1 && firstId != null) {
                        // Unambiguous single result — auto-select so the LLM doesn't need to repeat itself
                        lastArrivingProductId = firstId;
                        System.out.println("[SYSTEM] AI resolved product ID via search: " + lastArrivingProductId);
                    }
                    // Multiple results: do NOT set lastArrivingProductId — the LLM must list the
                    // options and wait for the worker to pick one before calling getProductAnalysis.
                    return searchResult;

                case "updateBinStatus":
                    if (allToPackingCounter) {
                        return TOOL_ERROR_PREFIX +
                               " PACKING_COUNTER_ONLY. All units are going to the Packing Counter — " +
                               "no bin update is needed. Wait for the worker to confirm 'done' instead.";
                    }
                    if (!workerJustConfirmed()) {
                        return TOOL_ERROR_PREFIX +
                               " updateBinStatus BLOCKED. Worker has not confirmed placement. " +
                               "Tell the worker the bin location and wait for 'done'.";
                    }
                    String binIdArg = args.get("binId").getAsString();
                    int qty = tentativeBinQtys.getOrDefault(binIdArg, lastArrivingQuantity > 0 ? lastArrivingQuantity : 0);
                    String binResult = InventoryTools.updateBinStatus(
                            binIdArg,
                            args.get("status").getAsString(),
                            args.get("productId").getAsString(),
                            qty);
                    if (!binResult.startsWith(TOOL_ERROR_PREFIX)) {
                        String logName = lastArrivingProductName.isEmpty()
                            ? lastArrivingProductId
                            : lastArrivingProductName + " (" + lastArrivingProductId + ")";
                        deliveryLog.add("[" + now() + "] " + logName
                            + " → Bin " + binIdArg);
                        // Remove this bin from the pending list so the Java commit loop
                        // in processAIResponse knows exactly which bins are still outstanding.
                        InventoryTools.refreshCapacityPct(Collections.singletonList(binIdArg));
                        tentativeBins.remove(binIdArg);
                        tentativeBinQtys.remove(binIdArg);
                        pendingUpdateCount = Math.max(0, pendingUpdateCount - 1);
                        if (pendingUpdateCount <= 0) {
                            lastArrivingProductId      = "";
                            lastArrivingProductName    = "";
                            lastPoCustomer             = "";
                            lastArrivingQuantity       = -1;
                            poCheckDone                = false;
                            allToPackingCounter        = false;
                            remainingQtyToBin          = 0;
                            tentativeBins.clear(); tentativeBinQtys.clear();
                            awaitingArrivalProduct     = false;
                            pendingBareProductId       = "";
                            awaitingQuantityFromWorker = false;
                        }
                    }
                    return binResult;

                case "reportAccident":
                    if (!recentMessageContainsEmergencyKeyword()) {
                        return TOOL_ERROR_PREFIX +
                               " reportAccident BLOCKED. No emergency keywords found in the worker's message. " +
                               "Do not fabricate emergencies. Ask the worker to describe what happened.";
                    }
                    String accidentResult = WarehouseSkills.reportAccident(
                            args.get("location").getAsString(),
                            args.get("description").getAsString());
                    if (!accidentResult.startsWith(TOOL_ERROR_PREFIX)) {
                        accidentLog.add("[" + now() + "] " + args.get("location").getAsString()
                            + " — " + args.get("description").getAsString());
                        lastArrivingProductId   = "";
                        lastArrivingProductName = "";
                        lastPoCustomer          = "";
                        lastArrivingQuantity    = -1;
                        poCheckDone             = false;
                        allToPackingCounter     = false;
                        remainingQtyToBin       = 0;
                        pendingUpdateCount      = 0;
                        tentativeBins.clear(); tentativeBinQtys.clear();
                    }
                    return accidentResult;

                case "clearAisle":
                    String clearResult = WarehouseSkills.clearAisle(args.get("location").getAsString());
                    if (!clearResult.startsWith(TOOL_ERROR_PREFIX)) {
                        clearedLog.add("[" + now() + "] " + args.get("location").getAsString());
                    }
                    return clearResult;

                default:
                    return TOOL_ERROR_PREFIX + " Unknown tool '" + funcName + "'";
            }
        } catch (Exception e) {
            return TOOL_ERROR_PREFIX + " Exception in '" + funcName + "': " + e.getMessage();
        }
    }

    private static final Pattern BIN_ID_PATTERN = Pattern.compile("[A-Z]\\d+-S\\d+-L\\d+-B\\d+");

    // Detect assistant replies that look like delivery routing (bin IDs, Packing Counter, unit splits).
    // Used to catch the model hallucinating an answer like "put 1 unit to packing counter and 1 unit to
    // bin A1-S1-L1-B1" when it has no real quantity and never called findOptimalBin.
    private static final Pattern ROUTING_INSTRUCTION = Pattern.compile(
        "(?i)\\bpacking\\s+counter\\b|\\b\\d+\\s+units?\\s+(?:to|in|into|at)\\s+(?:the\\s+)?(?:bin|packing)\\b|[A-Z]\\d+-S\\d+-L\\d+-B\\d+");

    private static boolean looksLikeRoutingInstruction(String content) {
        return content != null && ROUTING_INSTRUCTION.matcher(content).find();
    }

    // Scan recent real user messages for a token that looks like a product code (e.g. K15VC).
    // Used to recover lastArrivingProductId when the LLM hallucinated routing earlier without
    // ever calling getProductAnalysis, so the resume nudge can name the actual product.
    private static void recoverProductIdFromHistory() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if (!msg.has("role") || !msg.has("content")) continue;
            if (!"user".equals(msg.get("role").getAsString())) continue;
            String content = msg.get("content").getAsString();
            if (content.startsWith("[SYSTEM") || content.startsWith("[BATCH")) continue;
            Matcher m = PRODUCT_CODE_IN_MSG.matcher(content);
            if (m.find()) {
                String candidate = m.group().toUpperCase();
                if (!"Product not found.".equals(WarehouseSkills.getProductAnalysis(candidate))) {
                    lastArrivingProductId = candidate;
                    System.out.println("[SYSTEM] Recovered product ID from history: " + candidate);
                    return;
                }
            }
        }
    }

    private static String findLastBinId() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if (!msg.has("role") || !"tool".equals(msg.get("role").getAsString())) continue;
            if (!msg.has("content")) continue;
            Matcher m = BIN_ID_PATTERN.matcher(msg.get("content").getAsString());
            if (m.find()) return m.group();
        }
        return null;
    }

    private static boolean recentMessageContainsEmergencyKeyword() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if (!msg.has("role") || !msg.has("content")) continue;
            if ("user".equals(msg.get("role").getAsString())) {
                String content = msg.get("content").getAsString();
                if (content.startsWith("[SYSTEM")) continue;
                return EMERGENCY_KEYWORDS.matcher(content).find();
            }
        }
        return false;
    }

    private static final Pattern CONFIRMATION_WORDS = Pattern.compile(
        "\\b(done|placed|confirmed|ok|yes)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean workerJustConfirmed() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if (!msg.has("role") || !msg.has("content")) continue;
            if ("user".equals(msg.get("role").getAsString())) {
                String content = msg.get("content").getAsString();
                if (content.toLowerCase().startsWith("[system")) continue;
                return CONFIRMATION_WORDS.matcher(content).find();
            }
        }
        return false;
    }

    private static final String[] GREETING_TOKENS = {
        "hi", "hello", "hey", "morning", "afternoon", "evening", "yo", "sup", "howdy", "hiya", "greetings",
        "bro", "mate", "dude", "man", "guys", "oi", "wassup", "whatsup"
    };

    private static boolean isGreeting(String input) {
        String lower = input.toLowerCase().replaceAll("[^a-z ]", "").trim();
        for (String token : GREETING_TOKENS) {
            if (lower.equals(token) || lower.startsWith(token + " ") || lower.endsWith(" " + token)) {
                return true;
            }
        }
        return false;
    }

    // A lone token like "K15VC" or "PHL-9W-D" — no verb, no context. Ambiguous intent:
    // could be a description lookup, a location lookup, or a new shipment. Ask first.
    private static final Pattern BARE_PRODUCT_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9-]{2,19}$");

    private static boolean isBareProductId(String input) {
        String trimmed = input.trim();
        if (!BARE_PRODUCT_ID.matcher(trimmed).matches()) return false;
        // Must contain at least one digit — rules out bare words like "hello" or "done".
        return trimmed.matches(".*\\d.*");
    }

    // Returns true when the worker signals a stock arrival but names no specific product.
    // "A lorry arrived" or "delivery came in" triggers this; "lorry of K15VC arrived" does not
    // because PRODUCT_CODE_IN_MSG matches "K15VC".
    private static boolean isGenericArrival(String input) {
        return ARRIVAL_SIGNAL.matcher(input).find() && !PRODUCT_CODE_IN_MSG.matcher(input).find();
    }

    private static String generateShiftReport() {
        long elapsed = System.currentTimeMillis() - shiftStartTime;
        long hours   = elapsed / 3600000;
        long minutes = (elapsed % 3600000) / 60000;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 SHIFT REPORT\n");
        sb.append("🕐 Duration: ").append(hours).append("h ").append(minutes).append("m\n");

        if (deliveryLog.isEmpty() && accidentLog.isEmpty() && clearedLog.isEmpty()) {
            sb.append("\nNo events recorded this shift.");
        } else {
            if (!deliveryLog.isEmpty()) {
                sb.append("\n📦 Deliveries (").append(deliveryLog.size()).append(")\n");
                for (String e : deliveryLog) sb.append("  • ").append(e).append("\n");
            }
            if (!accidentLog.isEmpty()) {
                sb.append("\n🚨 Accidents (").append(accidentLog.size()).append(")\n");
                for (String e : accidentLog) sb.append("  • ").append(e).append("\n");
            }
            if (!clearedLog.isEmpty()) {
                sb.append("\n✅ Aisles Cleared (").append(clearedLog.size()).append(")\n");
                for (String e : clearedLog) sb.append("  • ").append(e).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("==============================================");
        System.out.println("   ZAI - WAREHOUSE INTELLIGENCE ONLINE        ");
        System.out.println("==============================================\n");

        while (true) {
            System.out.print("Worker: ");
            String userInput = scanner.nextLine().trim();
            if (userInput.equalsIgnoreCase("exit")) break;
            if (userInput.isEmpty()) continue;

            if (userInput.equalsIgnoreCase("report")) {
                String report = generateShiftReport();
                System.out.println("\n" + report);
                WarehouseSkills.notifyShiftReport(report);
                continue;
            }

            if (userInput.equalsIgnoreCase("cancel") || userInput.equalsIgnoreCase("reset")) {
                lastArrivingProductId      = "";
                lastArrivingProductName    = "";
                lastPoCustomer             = "";
                lastArrivingQuantity       = -1;
                poCheckDone                = false;
                allToPackingCounter        = false;
                remainingQtyToBin          = 0;
                pendingUpdateCount         = 0;
                tentativeBins.clear(); tentativeBinQtys.clear();
                awaitingArrivalProduct     = false;
                pendingBareProductId       = "";
                awaitingQuantityFromWorker = false;
                while (conversationHistory.size() > 0) conversationHistory.remove(0);
                System.out.println("\nZai: Workflow cancelled. Ready for a new task.");
                continue;
            }

            int prevArrivingQuantity = lastArrivingQuantity;
            if (!allToPackingCounter) tryUpdateArrivingQuantity(userInput);
            boolean justReceivedQuantity =
                awaitingQuantityFromWorker && prevArrivingQuantity <= 0 && lastArrivingQuantity > 0;

            // ── ARRIVAL PRODUCT LOOKUP ──────────────────────────────────────────────
            // Fired after isGenericArrival asked "what product?". Bypasses the bare-product-ID
            // disambiguation menu and does a direct DB lookup instead.
            if (awaitingArrivalProduct) {
                String input = userInput.trim();
                String pid   = input.toUpperCase();
                String reply;
                String analysis = WarehouseSkills.getProductAnalysis(pid);
                if (!"Product not found.".equals(analysis)) {
                    lastArrivingProductId = pid;
                    awaitingArrivalProduct = false;
                    allToPackingCounter    = false;
                    poCheckDone            = false;
                    remainingQtyToBin      = 0;
                    pendingUpdateCount     = 0;
                    tentativeBins.clear(); tentativeBinQtys.clear();
                    String productLabel = pid;
                    if (analysis.startsWith("Product: ")) {
                        int comma = analysis.indexOf(",");
                        if (comma > 9) productLabel = analysis.substring(9, comma).trim();
                    }
                    lastArrivingProductName = productLabel;
                    reply = "Found: " + analysis + ". How many units of " + productLabel + " arrived?";
                } else {
                    String searchResult = WarehouseSkills.searchProductByDescription(input);
                    if (searchResult.startsWith("No products found")) {
                        reply = "\"" + input + "\" was not found in the system. Please check the product ID or name and try again.";
                    } else {
                        Matcher sm = Pattern
                            .compile("(?i)(?:id[:\\s]+)([A-Z0-9][A-Z0-9\\-]{1,14})")
                            .matcher(searchResult);
                        String sFirstId = null; int sCount = 0;
                        while (sm.find()) { sCount++; if (sFirstId == null) sFirstId = sm.group(1).toUpperCase(); }
                        if (sCount == 1 && sFirstId != null) {
                            lastArrivingProductId = sFirstId;
                            awaitingArrivalProduct = false;
                            allToPackingCounter    = false;
                            poCheckDone            = false;
                            remainingQtyToBin      = 0;
                            pendingUpdateCount     = 0;
                            tentativeBins.clear(); tentativeBinQtys.clear();
                            String detail = WarehouseSkills.getProductAnalysis(sFirstId);
                            String productLabel = sFirstId;
                            if (detail.startsWith("Product: ")) {
                                int comma = detail.indexOf(",");
                                if (comma > 9) productLabel = detail.substring(9, comma).trim();
                            }
                            lastArrivingProductName = productLabel;
                            reply = "Found: " + detail + ". How many units of " + productLabel + " arrived?";
                        } else if (sCount > 1) {
                            reply = "I found multiple products matching \"" + input + "\":\n"
                                  + searchResult + "Please give me the exact product ID or a more specific name.";
                        } else {
                            reply = "\"" + input + "\" was not found in the system. Please check the product ID or name and try again.";
                        }
                    }
                }
                System.out.println("\nZai: " + reply);
                JsonObject uMsg = new JsonObject(); uMsg.addProperty("role", "user"); uMsg.addProperty("content", userInput); conversationHistory.add(uMsg);
                JsonObject aMsg = new JsonObject(); aMsg.addProperty("role", "assistant"); aMsg.addProperty("content", reply); conversationHistory.add(aMsg);
                continue;
            }

            // ── BARE-PRODUCT-ID DISAMBIGUATION (a/b/c response) ────────────────────
            if (!pendingBareProductId.isEmpty()) {
                String pid = pendingBareProductId;
                String stripped = userInput.trim().toLowerCase().replaceAll("[^a-z]", "");
                char choice = stripped.isEmpty() ? ' ' : stripped.charAt(0);
                if (choice == 'a' || choice == 'b' || choice == 'c') {
                    String reply;
                    if (choice == 'a') {
                        reply = "Details for " + pid + ": " + WarehouseSkills.getProductAnalysis(pid);
                    } else if (choice == 'b') {
                        reply = WarehouseSkills.findProductLocation(pid);
                    } else {
                        lastArrivingProductId  = pid;
                        allToPackingCounter    = false;
                        poCheckDone            = false;
                        remainingQtyToBin      = 0;
                        pendingUpdateCount     = 0;
                        tentativeBins.clear(); tentativeBinQtys.clear();
                        lastArrivingQuantity   = -1;
                        String analysis = WarehouseSkills.getProductAnalysis(pid);
                        String productLabel = pid;
                        if (analysis.startsWith("Product: ")) { int c = analysis.indexOf(","); if (c > 9) productLabel = analysis.substring(9, c).trim(); }
                        JsonObject sysMsg = new JsonObject();
                        sysMsg.addProperty("role", "user");
                        sysMsg.addProperty("content", "[SYSTEM DELIVERY STARTED] Product " + pid + " identified. " + analysis + ". STEP 1 complete — proceed from STEP 1b (ask for quantity only).");
                        conversationHistory.add(sysMsg);
                        reply = "Got it — how many units of " + productLabel + " arrived?";
                    }
                    pendingBareProductId = "";
                    System.out.println("\nZai: " + reply);
                    JsonObject uMsg = new JsonObject(); uMsg.addProperty("role", "user"); uMsg.addProperty("content", userInput); conversationHistory.add(uMsg);
                    JsonObject aMsg = new JsonObject(); aMsg.addProperty("role", "assistant"); aMsg.addProperty("content", reply); conversationHistory.add(aMsg);
                    continue;
                }
                pendingBareProductId = ""; // unrecognised — drop menu state and fall through
            }

            if (isGreeting(userInput)) {
                String greetReply = "Hey! I'm Zai, your warehouse intelligence system. Ready to help!";
                System.out.println("\nZai: " + greetReply);
                JsonObject uMsg = new JsonObject();
                uMsg.addProperty("role", "user");
                uMsg.addProperty("content", userInput);
                conversationHistory.add(uMsg);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", greetReply);
                conversationHistory.add(aMsg);
                continue;
            }

            if (isBareProductId(userInput)) {
                String pid = userInput.trim().toUpperCase();
                // Check the DB immediately — never show a menu for a product that doesn't exist
                String analysis = WarehouseSkills.getProductAnalysis(pid);
                String reply;
                if ("Product not found.".equals(analysis)) {
                    reply = "Product ID \"" + pid + "\" was not found in the system. Please verify the product ID and try again.";
                } else {
                    // Product exists — show menu and remember which product we're asking about
                    pendingBareProductId = pid;
                    reply = "Found " + pid + ". Do you want (a) the product description, "
                          + "(b) its current location in the warehouse, or "
                          + "(c) to report new stock that just arrived?";
                }
                System.out.println("\nZai: " + reply);
                JsonObject uMsg = new JsonObject();
                uMsg.addProperty("role", "user");
                uMsg.addProperty("content", userInput);
                conversationHistory.add(uMsg);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                continue;
            }

            // When all units go to the Packing Counter, no bin update is needed.
            // Intercept the worker's confirmation directly and reset state.
            if (allToPackingCounter) {
                String lower = userInput.toLowerCase().trim();
                boolean confirmed = lower.contains("done") || lower.contains("placed")
                        || lower.contains("confirmed") || lower.contains("ok") || lower.contains("yes");
                if (confirmed) {
                    String logName = lastArrivingProductName.isEmpty()
                        ? lastArrivingProductId
                        : lastArrivingProductName + " (" + lastArrivingProductId + ")";
                    String customerSuffix = lastPoCustomer.isEmpty() ? "" : " (" + lastPoCustomer + ")";
                    deliveryLog.add("[" + now() + "] " + lastArrivingQuantity + "x " + logName
                        + " → Packing Counter" + customerSuffix);
                    String reply = "Delivery complete. All units have been sent to the Packing Counter. Good work!";
                    System.out.println("\nZai: " + reply);
                    JsonObject uMsg = new JsonObject(); uMsg.addProperty("role", "user"); uMsg.addProperty("content", userInput); conversationHistory.add(uMsg);
                    JsonObject aMsg = new JsonObject(); aMsg.addProperty("role", "assistant"); aMsg.addProperty("content", reply); conversationHistory.add(aMsg);
                    lastArrivingProductId      = "";
                    lastArrivingProductName    = "";
                    lastPoCustomer             = "";
                    lastArrivingQuantity       = -1;
                    poCheckDone                = false;
                    allToPackingCounter        = false;
                    remainingQtyToBin          = 0;
                    pendingUpdateCount         = 0;
                    tentativeBins.clear(); tentativeBinQtys.clear();
                    awaitingArrivalProduct     = false;
                    pendingBareProductId       = "";
                    awaitingQuantityFromWorker = false;
                    continue;
                }
            }

            if (isGenericArrival(userInput) && lastArrivingProductId.isEmpty()) {
                awaitingArrivalProduct = true;
                String reply = "Got it — what is the product ID or name of the stock that arrived?";
                System.out.println("\nZai: " + reply);
                JsonObject uMsg = new JsonObject();
                uMsg.addProperty("role", "user");
                uMsg.addProperty("content", userInput);
                conversationHistory.add(uMsg);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
                continue;
            }

            // Specific arrival: an arrival verb AND a product-code token in the same message
            // (e.g. "a pallet of 20 K15VC arrived"). isGenericArrival deliberately skips this case,
            // so without a dedicated branch the message falls through to the LLM and sometimes gets
            // misrouted to PROTOCOL QUERY — the model calls findProductLocation and replies
            // "there is no K15VC in the warehouse" even though the worker reported a delivery.
            // Pin the product in Java and inject a [SYSTEM DELIVERY STARTED] nudge so the LLM
            // goes straight to STEP 2 of the delivery workflow.
            if (lastArrivingProductId.isEmpty() && ARRIVAL_SIGNAL.matcher(userInput).find()) {
                Matcher pcm = PRODUCT_CODE_IN_MSG.matcher(userInput);
                if (pcm.find()) {
                    String pid = pcm.group().toUpperCase();
                    String analysis = WarehouseSkills.getProductAnalysis(pid);
                    String resolvedId = null;
                    String resolvedDetail = null;

                    if (!"Product not found.".equals(analysis)) {
                        resolvedId = pid;
                        resolvedDetail = analysis;
                    } else {
                        // Typo recovery: try a fuzzy search against the typed token.
                        String searchResult = WarehouseSkills.searchProductByDescription(pid);
                        if (!searchResult.startsWith("No products found")) {
                            Matcher sm = Pattern
                                .compile("(?i)(?:id[:\\s]+)([A-Z0-9][A-Z0-9\\-]{1,14})")
                                .matcher(searchResult);
                            String sFirstId = null; int sCount = 0;
                            while (sm.find()) { sCount++; if (sFirstId == null) sFirstId = sm.group(1).toUpperCase(); }
                            if (sCount == 1 && sFirstId != null) {
                                resolvedId = sFirstId;
                                resolvedDetail = WarehouseSkills.getProductAnalysis(sFirstId);
                            }
                        }
                    }

                    if (resolvedId != null) {
                        lastArrivingProductId = resolvedId;
                        allToPackingCounter   = false;
                        poCheckDone           = false;
                        remainingQtyToBin     = 0;
                        pendingUpdateCount    = 0;
                        tentativeBins.clear(); tentativeBinQtys.clear();
                        String productLabel = resolvedId;
                        if (resolvedDetail != null && resolvedDetail.startsWith("Product: ")) {
                            int comma = resolvedDetail.indexOf(",");
                            if (comma > 9) {
                                lastArrivingProductName = resolvedDetail.substring(9, comma).trim();
                                productLabel = lastArrivingProductName;
                            }
                        }
                        System.out.println("[SYSTEM] Arrival detected — product: " + resolvedId
                                + (lastArrivingQuantity > 0 ? ", quantity: " + lastArrivingQuantity : ", quantity: pending"));

                        JsonObject uMsg = new JsonObject();
                        uMsg.addProperty("role", "user");
                        uMsg.addProperty("content", userInput);
                        conversationHistory.add(uMsg);

                        String productRef = productLabel.equals(resolvedId) ? resolvedId : productLabel + " (" + resolvedId + ")";
                        String nudgeContent;
                        if (lastArrivingQuantity > 0) {
                            nudgeContent =
                                "[SYSTEM DELIVERY STARTED] This is a stock arrival, NOT a lookup. " +
                                "Product: " + productRef + ". Arriving quantity: " + lastArrivingQuantity + " units. " +
                                "STEP 1 and STEP 1b are complete. Proceed to STEP 2: call readLocalCustomerOrder " +
                                "and follow the [CO MATCH FOUND] / [NO CO MATCH] routing exactly. " +
                                "DO NOT call findProductLocation. DO NOT reply that the product is 'not in the warehouse'.";
                        } else {
                            nudgeContent =
                                "[SYSTEM DELIVERY STARTED] This is a stock arrival, NOT a lookup. " +
                                "Product: " + productRef + ". STEP 1 is complete. Proceed to STEP 1b: reply with " +
                                "ONLY the question 'How many units of " + productLabel + " arrived?' and wait for an answer. " +
                                "DO NOT call findProductLocation. DO NOT reply that the product is 'not in the warehouse'.";
                            awaitingQuantityFromWorker = true;
                        }
                        JsonObject nudge = new JsonObject();
                        nudge.addProperty("role", "user");
                        nudge.addProperty("content", nudgeContent);
                        conversationHistory.add(nudge);

                        System.out.println("[Zai is thinking...]");
                        processAIResponse(callAPI(conversationHistory), 0);
                        continue;
                    }
                    // No unique match — fall through to the LLM, which will handle resolution
                    // via searchProductByDescription under the DELIVERY WORKFLOW prompt.
                }
            }

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userInput);
            conversationHistory.add(userMsg);

            // Resume nudge: once the worker answers the quantity question, deterministically
            // tell the LLM what to do next. Without this, the model often stalls or re-asks
            // instead of continuing to readLocalCustomerOrder → findOptimalBin.
            if (justReceivedQuantity) {
                // If the LLM never resolved the product (e.g. it hallucinated earlier without
                // calling getProductAnalysis), recover the product code from recent user messages
                // so the nudge can be specific.
                if (lastArrivingProductId.isEmpty()) {
                    recoverProductIdFromHistory();
                }

                StringBuilder nudge = new StringBuilder("[SYSTEM QUANTITY RECEIVED] Arriving quantity = ")
                    .append(lastArrivingQuantity).append(" units");
                if (!lastArrivingProductId.isEmpty()) nudge.append(" of ").append(lastArrivingProductId);
                nudge.append(". Resume the delivery workflow now. ");
                if (lastArrivingProductId.isEmpty()) {
                    nudge.append("First call getProductAnalysis (or searchProductByDescription) to resolve the product ID, ")
                         .append("then call readLocalCustomerOrder, then follow its routing instruction. ");
                } else if (!poCheckDone) {
                    nudge.append("Call readLocalCustomerOrder next, then follow the [CO MATCH FOUND] / [NO CO MATCH] routing instruction exactly. ");
                } else {
                    nudge.append("Call findOptimalBin next and tell the worker the bin ID. ");
                }
                nudge.append("Do NOT ask for the quantity again.");

                JsonObject sysNudge = new JsonObject();
                sysNudge.addProperty("role", "user");
                sysNudge.addProperty("content", nudge.toString());
                conversationHistory.add(sysNudge);
                awaitingQuantityFromWorker = false;
            }

            System.out.println("[Zai is thinking...]");
            processAIResponse(callAPI(conversationHistory), 0);
        }

        scanner.close();
    }

    public static synchronized String getAIResponseForWeb(String userMessage) {
        try {
            // 1. DETERMINISTIC PRE-SCREENING (Always runs first)
            int prevArrivingQuantity = lastArrivingQuantity;
            if (!allToPackingCounter) tryUpdateArrivingQuantity(userMessage);
            boolean justReceivedQuantity =
                awaitingQuantityFromWorker && prevArrivingQuantity <= 0 && lastArrivingQuantity > 0;

            // 2. STATE: ARRIVAL PRODUCT LOOKUP (The "what product?" flow)
            if (awaitingArrivalProduct) {
                String result = handleArrivalLookup(userMessage);
                recordMessage("user", userMessage);
                recordMessage("assistant", result);
                return result;
            }

            // 3. STATE: BARE-PRODUCT-ID MENU (The a/b/c choice flow)
            if (!pendingBareProductId.isEmpty()) {
                String result = handleMenuChoice(userMessage);
                if (result != null) {
                    recordMessage("user", userMessage);
                    recordMessage("assistant", result);
                    return result;
                }
            }

            // 4. GATE: GREETINGS
            if (isGreeting(userMessage)) {
                String reply = "Hey! I'm Zai, your warehouse intelligence system. Ready to help!";
                recordMessage("user", userMessage);
                recordMessage("assistant", reply);
                return reply;
            }

            // 5. GATE: BARE PRODUCT ID DETECTION (Menu Trigger)
            if (isBareProductId(userMessage)) {
                String pid = userMessage.trim().toUpperCase();
                String analysis = WarehouseSkills.getProductAnalysis(pid);
                String reply;
                if ("Product not found.".equals(analysis)) {
                    reply = "Product ID \"" + pid + "\" was not found in the system.";
                } else {
                    pendingBareProductId = pid;
                    reply = "Found " + pid + ". Do you want (a) description, (b) location, or (c) report stock?";
                }
                recordMessage("user", userMessage);
                recordMessage("assistant", reply);
                return reply;
            }

            // 6. GATE: PACKING-COUNTER CONFIRMATION (Bug 7)
            // When all units go to the Packing Counter, intercept "done" directly
            // so state resets without needing the AI brain.
            if (allToPackingCounter) {
                String lower = userMessage.toLowerCase().trim();
                boolean confirmed = lower.contains("done") || lower.contains("placed")
                        || lower.contains("confirmed") || lower.contains("ok") || lower.contains("yes");
                if (confirmed) {
                    String logName = lastArrivingProductName.isEmpty()
                        ? lastArrivingProductId
                        : lastArrivingProductName + " (" + lastArrivingProductId + ")";
                    String customerSuffix = lastPoCustomer.isEmpty() ? "" : " (" + lastPoCustomer + ")";
                    deliveryLog.add("[" + now() + "] " + lastArrivingQuantity + "x " + logName
                        + " → Packing Counter" + customerSuffix);
                    String reply = "Delivery complete. All units have been sent to the Packing Counter. Good work!";
                    recordMessage("user", userMessage);
                    recordMessage("assistant", reply);
                    lastArrivingProductId      = "";
                    lastArrivingProductName    = "";
                    lastPoCustomer             = "";
                    lastArrivingQuantity       = -1;
                    poCheckDone                = false;
                    allToPackingCounter        = false;
                    remainingQtyToBin          = 0;
                    pendingUpdateCount         = 0;
                    tentativeBins.clear(); tentativeBinQtys.clear();
                    awaitingArrivalProduct     = false;
                    pendingBareProductId       = "";
                    awaitingQuantityFromWorker = false;
                    return reply;
                }
            }

            // 7. GATE: GENERIC ARRIVAL
            if (isGenericArrival(userMessage) && lastArrivingProductId.isEmpty()) {
                awaitingArrivalProduct = true;
                String reply = "Got it — what is the product ID or name of the stock that arrived?";
                recordMessage("user", userMessage);
                recordMessage("assistant", reply);
                return reply;
            }

            // 8. GATE: SPECIFIC ARRIVAL — arrival verb + product code in same message (Bug 8)
            // e.g. "a pallet of 20 K15VC arrived". Without this the LLM misroutes to
            // PROTOCOL QUERY and says the product is not in the warehouse.
            if (lastArrivingProductId.isEmpty() && ARRIVAL_SIGNAL.matcher(userMessage).find()) {
                Matcher pcm = PRODUCT_CODE_IN_MSG.matcher(userMessage);
                if (pcm.find()) {
                    String pid = pcm.group().toUpperCase();
                    String analysis = WarehouseSkills.getProductAnalysis(pid);
                    String resolvedId = null;
                    String resolvedDetail = null;

                    if (!"Product not found.".equals(analysis)) {
                        resolvedId = pid;
                        resolvedDetail = analysis;
                    } else {
                        String searchResult = WarehouseSkills.searchProductByDescription(pid);
                        if (!searchResult.startsWith("No products found")) {
                            Matcher sm = Pattern
                                .compile("(?i)(?:id[:\\s]+)([A-Z0-9][A-Z0-9\\-]{1,14})")
                                .matcher(searchResult);
                            String sFirstId = null; int sCount = 0;
                            while (sm.find()) { sCount++; if (sFirstId == null) sFirstId = sm.group(1).toUpperCase(); }
                            if (sCount == 1 && sFirstId != null) {
                                resolvedId = sFirstId;
                                resolvedDetail = WarehouseSkills.getProductAnalysis(sFirstId);
                            }
                        }
                    }

                    if (resolvedId != null) {
                        lastArrivingProductId = resolvedId;
                        allToPackingCounter   = false;
                        poCheckDone           = false;
                        remainingQtyToBin     = 0;
                        pendingUpdateCount    = 0;
                        tentativeBins.clear(); tentativeBinQtys.clear();
                        String productLabel = resolvedId;
                        if (resolvedDetail != null && resolvedDetail.startsWith("Product: ")) {
                            int comma = resolvedDetail.indexOf(",");
                            if (comma > 9) {
                                lastArrivingProductName = resolvedDetail.substring(9, comma).trim();
                                productLabel = lastArrivingProductName;
                            }
                        }

                        recordMessage("user", userMessage);
                        String productRef = productLabel.equals(resolvedId) ? resolvedId : productLabel + " (" + resolvedId + ")";
                        String nudgeContent;
                        if (lastArrivingQuantity > 0) {
                            nudgeContent =
                                "[SYSTEM DELIVERY STARTED] This is a stock arrival, NOT a lookup. " +
                                "Product: " + productRef + ". Arriving quantity: " + lastArrivingQuantity + " units. " +
                                "STEP 1 and STEP 1b are complete. Proceed to STEP 2: call readLocalCustomerOrder " +
                                "and follow the [CO MATCH FOUND] / [NO CO MATCH] routing exactly. " +
                                "DO NOT call findProductLocation. DO NOT reply that the product is 'not in the warehouse'.";
                        } else {
                            nudgeContent =
                                "[SYSTEM DELIVERY STARTED] This is a stock arrival, NOT a lookup. " +
                                "Product: " + productRef + ". STEP 1 is complete. Proceed to STEP 1b: reply with " +
                                "ONLY the question 'How many units of " + productLabel + " arrived?' and wait for an answer. " +
                                "DO NOT call findProductLocation. DO NOT reply that the product is 'not in the warehouse'.";
                            awaitingQuantityFromWorker = true;
                        }
                        JsonObject nudge = new JsonObject();
                        nudge.addProperty("role", "user");
                        nudge.addProperty("content", nudgeContent);
                        conversationHistory.add(nudge);

                        int turnStart2 = conversationHistory.size();
                        processAIResponse(callAPI(conversationHistory), 0);
                        for (int i = conversationHistory.size() - 1; i >= turnStart2; i--) {
                            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
                            if (!msg.has("role") || !"assistant".equals(msg.get("role").getAsString())) continue;
                            JsonElement contentEl = msg.get("content");
                            if (contentEl == null || contentEl.isJsonNull()) continue;
                            String content = contentEl.getAsString();
                            if (content.isBlank() || content.startsWith("[SYSTEM")) continue;
                            return content;
                        }
                        return "Zai is still processing that — please try again.";
                    }
                }
            }

            // 9. THE AI BRAIN (For everything else: complex queries, tool-calling)
            recordMessage("user", userMessage);

            // Resume nudge: once the worker answers the quantity question, inject a
            // [SYSTEM QUANTITY RECEIVED] nudge so the LLM continues the workflow
            // instead of stalling or re-asking. (Bug 9)
            if (justReceivedQuantity) {
                if (lastArrivingProductId.isEmpty()) recoverProductIdFromHistory();

                StringBuilder nudge = new StringBuilder("[SYSTEM QUANTITY RECEIVED] Arriving quantity = ")
                    .append(lastArrivingQuantity).append(" units");
                if (!lastArrivingProductId.isEmpty()) nudge.append(" of ").append(lastArrivingProductId);
                nudge.append(". Resume the delivery workflow now. ");
                if (lastArrivingProductId.isEmpty()) {
                    nudge.append("First call getProductAnalysis (or searchProductByDescription) to resolve the product ID, ")
                         .append("then call readLocalCustomerOrder, then follow its routing instruction. ");
                } else if (!poCheckDone) {
                    nudge.append("Call readLocalCustomerOrder next, then follow the [CO MATCH FOUND] / [NO CO MATCH] routing instruction exactly. ");
                } else {
                    nudge.append("Call findOptimalBin next and tell the worker the bin ID. ");
                }
                nudge.append("Do NOT ask for the quantity again.");

                JsonObject sysNudge = new JsonObject();
                sysNudge.addProperty("role", "user");
                sysNudge.addProperty("content", nudge.toString());
                conversationHistory.add(sysNudge);
                awaitingQuantityFromWorker = false;
            }

            int turnStart = conversationHistory.size();
            String jsonResponse = callAPI(conversationHistory);
            processAIResponse(jsonResponse, 0);

            // Scan back for the actual worker-facing assistant reply.
            for (int i = conversationHistory.size() - 1; i >= turnStart; i--) {
                JsonObject msg = conversationHistory.get(i).getAsJsonObject();
                if (!msg.has("role") || !"assistant".equals(msg.get("role").getAsString())) continue;
                JsonElement contentEl = msg.get("content");
                if (contentEl == null || contentEl.isJsonNull()) continue;
                String content = contentEl.getAsString();
                if (content.isBlank() || content.startsWith("[SYSTEM")) continue;
                return content;
            }
            return "Zai is still processing that — please try again.";

        } catch (Exception e) {
            e.printStackTrace();
            return "Zai System Error: " + e.getMessage();
        }
    }

    private static String handleArrivalLookup(String userInput) {
        String pid = userInput.trim().toUpperCase();
        String analysis = WarehouseSkills.getProductAnalysis(pid);
        if (!"Product not found.".equals(analysis)) {
            lastArrivingProductId = pid;
            awaitingArrivalProduct = false;
            allToPackingCounter = false;
            poCheckDone = false;
            remainingQtyToBin = 0;
            pendingUpdateCount = 0;
            tentativeBins.clear(); tentativeBinQtys.clear();
            String productLabel = pid;
            if (analysis.startsWith("Product: ")) {
                int comma = analysis.indexOf(",");
                if (comma > 9) productLabel = analysis.substring(9, comma).trim();
            }
            lastArrivingProductName = productLabel;
            return "Found: " + analysis + ". How many units of " + productLabel + " arrived?";
        }
        String searchResult = WarehouseSkills.searchProductByDescription(userInput.trim());
        if (!searchResult.startsWith("No products found")) {
            Matcher sm = Pattern
                .compile("(?i)(?:id[:\\s]+)([A-Z0-9][A-Z0-9\\-]{1,14})")
                .matcher(searchResult);
            String sFirstId = null; int sCount = 0;
            while (sm.find()) { sCount++; if (sFirstId == null) sFirstId = sm.group(1).toUpperCase(); }
            if (sCount == 1 && sFirstId != null) {
                String detail = WarehouseSkills.getProductAnalysis(sFirstId);
                lastArrivingProductId = sFirstId;
                awaitingArrivalProduct = false;
                allToPackingCounter = false;
                poCheckDone = false;
                remainingQtyToBin = 0;
                pendingUpdateCount = 0;
                tentativeBins.clear(); tentativeBinQtys.clear();
                String productLabel = sFirstId;
                if (detail.startsWith("Product: ")) {
                    int comma = detail.indexOf(",");
                    if (comma > 9) productLabel = detail.substring(9, comma).trim();
                }
                lastArrivingProductName = productLabel;
                return "Found: " + detail + ". How many units of " + productLabel + " arrived?";
            } else if (sCount > 1) {
                return "I found multiple products matching \"" + userInput.trim() + "\":\n"
                     + searchResult + "Please give me the exact product ID or a more specific name.";
            }
        }
        return "\"" + userInput.trim() + "\" was not found in the system. Please check the product ID or name and try again.";
    }

    private static String handleMenuChoice(String userInput) {
        String stripped = userInput.trim().toLowerCase().replaceAll("[^a-z]", "");
        char choice = stripped.isEmpty() ? ' ' : stripped.charAt(0);
        String pid = pendingBareProductId;
        
        if (choice == 'a' || choice == 'b' || choice == 'c') {
            pendingBareProductId = "";
            if (choice == 'a') return "Details for " + pid + ": " + WarehouseSkills.getProductAnalysis(pid);
            if (choice == 'b') return WarehouseSkills.findProductLocation(pid);
            
            lastArrivingProductId      = pid;
            allToPackingCounter        = false;
            poCheckDone                = false;
            remainingQtyToBin          = 0;
            pendingUpdateCount         = 0;
            lastArrivingQuantity       = -1;
            tentativeBins.clear(); tentativeBinQtys.clear();
            String analysis = WarehouseSkills.getProductAnalysis(pid);
            String productLabel = pid;
            if (analysis.startsWith("Product: ")) {
                int comma = analysis.indexOf(",");
                if (comma > 9) productLabel = analysis.substring(9, comma).trim();
            }
            lastArrivingProductName = productLabel;
            return "Got it — how many units of " + productLabel + " arrived?";
        }
        pendingBareProductId = ""; // Reset if invalid choice
        return null;
    }
    
    // Helper to keep history clean
    private static void recordMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        conversationHistory.add(msg);
    }
}