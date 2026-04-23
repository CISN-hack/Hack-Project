import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AIAgent {

    private static final String API_KEY   = "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";
    private static final String MODEL_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String MODEL_ID  = "meta/llama-3.3-70b-instruct";

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
        "          · 1+ bins found → 'Yes — [product name] is stored in [N] bin(s): [bin list].'\n" +
        "  • WHOLE WAREHOUSE — if the worker asks for the FULL inventory (e.g. 'what products do we have?', 'list everything in stock', 'show warehouse inventory'):\n" +
        "      – Call 'listWarehouseInventory' (no arguments).\n" +
        "      – Present the tool result directly to the worker (it is already formatted as a list).\n" +
        "  • DO NOT call readLocalPurchaseOrder, findOptimalBin, or updateBinStatus. Lookups are read-only.\n" +
        "  • DO NOT start the delivery workflow.\n\n" +

        "DELIVERY WORKFLOW (follow every step in order, no skipping):\n\n" +

        "STEP 1 — IDENTIFY THE PRODUCT:\n" +
        "  • A deterministic pre-screen runs before you see the message: if the worker said only that stock/a lorry/a delivery arrived with NO product specified, Java already replied asking for the product — you will receive the product name or ID in the next message.\n" +
        "  • If the worker gave a product ID (e.g. 'K15VC'), call 'getProductAnalysis' with that ID.\n" +
        "  • If the worker described a product by name (e.g. 'ceiling fan'), call 'searchProductByDescription' first.\n" +
        "  • If 'searchProductByDescription' returns EXACTLY ONE product, call 'getProductAnalysis' with that ID immediately.\n" +
        "  • If 'searchProductByDescription' returns MORE THAN ONE product, list all results to the worker and ask which one arrived. STOP — do NOT call 'getProductAnalysis' until the worker picks one.\n" +
        "  • Do NOT proceed until you have a confirmed product ID.\n\n" +

        "STEP 1b — GET ARRIVING QUANTITY:\n" +
        "  • Ask the worker: 'How many units of [product name] arrived?'\n" +
        "  • Do NOT proceed until the worker gives you a number.\n" +
        "  • Store this number — you will need it to decide routing in STEP 2.\n\n" +

        "STEP 2 — CHECK THE PURCHASE ORDER:\n" +
        "  • Call 'readLocalPurchaseOrder'.\n" +
        "  • A [PO MATCH FOUND] or [NO PO MATCH] message will be injected automatically after the result.\n" +
        "  • Read it carefully and follow its routing instruction exactly.\n\n" +

        "STEP 3 — BINNING (only if needed):\n" +
        "  • Use the product analysis from Step 1 to calculate weight/volume for the units being binned.\n" +
        "  • Call 'findOptimalBin' with those values.\n\n" +

        "STEP 4 — TELL THE WORKER (CRITICAL — DO NOT SKIP):\n" +
        "  • After 'findOptimalBin' returns, STOP calling tools immediately.\n" +
        "  • Reply with a TEXT MESSAGE ONLY (no tool calls).\n" +
        "  • The message MUST include:\n" +
        "      – How many units go to the Packing Counter (if any)\n" +
        "      – How many units go to the bin, and the exact bin ID\n" +
        "      – End with: 'Reply \"done\" when you have placed the items.'\n" +
        "  • STOP. Do not call any tool until the worker replies with 'done'.\n\n" +

        "STEP 5 — COMMIT (only after worker confirms):\n" +
        "  • Only if the worker's latest message is 'done', 'placed', 'confirmed', or similar, call 'updateBinStatus'.\n" +
        "  • NEVER call updateBinStatus right after findOptimalBin — it WILL be rejected.\n\n" +

        "Refuse new tasks until the current one is confirmed.";

    private static final String TOOL_ERROR_PREFIX = "TOOL_DISPATCH_ERROR:";
    private static final JsonArray conversationHistory = new JsonArray();

    private static String lastArrivingProductId = "";
    private static int lastArrivingQuantity = -1;
    private static boolean awaitingArrivalProduct = false;
    private static String pendingBareProductId = "";
    private static boolean poCheckDone = false;
    private static boolean allToPackingCounter = false; // true when PO match covers all arriving units

    private static final Pattern STANDALONE_NUMBER = Pattern.compile("\\b(\\d{1,6})\\b");
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
        try {
            return JsonParser.parseString(Files.readString(Paths.get("tools.json"))).getAsJsonArray();
        } catch (Exception e) {
            System.out.println("CRITICAL: tools.json not found!");
            return new JsonArray();
        }
    }

    // Deterministic PO matching done in Java so the model can't miss or misread a hit.
    // Injected as a user-role message after readLocalPurchaseOrder so the model sees explicit routing.
    private static String buildPoMatchMessage(String csvData, String arrivingProductId) {
        if (arrivingProductId == null || arrivingProductId.isBlank()) {
            return "[PO MATCH SKIPPED] No arriving product ID was recorded. Ask the worker for the product ID.";
        }

        String[] lines = csvData.split("\\r?\\n");
        if (lines.length < 2) {
            return "[NO PO MATCH] PO file is empty or unreadable. Bin all arriving units.";
        }

        String[] headers = lines[0].split(",");
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
            return "[PO MATCH ERROR] Could not find ProductID column in PO file. Bin all arriving units.";
        }

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] cols = lines[i].split(",");
            if (cols.length <= colProductId) continue;

            String poProductId = cols[colProductId].trim();
            if (poProductId.equalsIgnoreCase(arrivingProductId.trim())) {
                String customer = (colCustomer >= 0 && colCustomer < cols.length)
                        ? cols[colCustomer].trim() : "Unknown Customer";
                String qty = (colQty >= 0 && colQty < cols.length)
                        ? cols[colQty].trim() : "?";
                String priority = (colPriority >= 0 && colPriority < cols.length)
                        ? cols[colPriority].trim() : "Standard";

                return "[PO MATCH FOUND]\n" +
                       "Product : " + poProductId + "\n" +
                       "Customer: " + customer + "\n" +
                       "PO Qty  : " + qty + " units\n" +
                       "Priority: " + priority + "\n\n" +
                       "ROUTING INSTRUCTION:\n" +
                       "Route " + qty + " units to the Packing Counter for " + customer +
                       " (" + priority + " priority).\n" +
                       "If arriving qty > " + qty + ", bin the remainder using getProductAnalysis + findOptimalBin.\n" +
                       "If arriving qty <= " + qty + ", send ALL to Packing Counter — do NOT bin anything.";
            }
        }

        return "[NO PO MATCH] Product '" + arrivingProductId + "' is not in the current PO.\n" +
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

        HttpClient client = HttpClient.newHttpClient();
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
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
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
            boolean accidentReported = false;
            boolean aisleCleared = false;
            boolean quantityMissing = false;

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

                if ("readLocalPurchaseOrder".equals(funcName)) {
                    String matchResult = buildPoMatchMessage(toolOutput, lastArrivingProductId);
                    System.out.println("\n[SYSTEM PO CHECK]\n" + matchResult);

                    JsonObject poCheckMsg = new JsonObject();
                    poCheckMsg.addProperty("role", "user");
                    poCheckMsg.addProperty("content",
                        "[SYSTEM PO CHECK — READ THIS BEFORE DOING ANYTHING ELSE]\n" + matchResult);
                    conversationHistory.add(poCheckMsg);

                    // Notify managers via Telegram when arriving stock matches a PO
                    if (matchResult.startsWith("[PO MATCH FOUND]")) {
                        String customer = "Unknown";
                        String poQty    = "?";
                        String priority = "Standard";
                        for (String line : matchResult.split("\\r?\\n")) {
                            if (line.startsWith("Customer:")) customer = line.substring("Customer:".length()).trim();
                            else if (line.startsWith("PO Qty  :")) poQty = line.substring("PO Qty  :".length()).trim();
                            else if (line.startsWith("Priority:")) priority = line.substring("Priority:".length()).trim();
                        }
                        WarehouseSkills.notifyPoArrival(lastArrivingProductId, customer, poQty, priority, lastArrivingQuantity);

                        // Detect "all to Packing Counter" case: arriving qty <= PO qty means no binning
                        try {
                            int poQtyNum = Integer.parseInt(poQty.replaceAll("[^0-9]", ""));
                            if (lastArrivingQuantity <= poQtyNum) {
                                allToPackingCounter = true;
                                JsonObject packMsg = new JsonObject();
                                packMsg.addProperty("role", "user");
                                packMsg.addProperty("content",
                                    "[SYSTEM PACKING ONLY] All " + lastArrivingQuantity + " units go to the Packing Counter for "
                                    + customer + ". Do NOT call findOptimalBin or updateBinStatus. "
                                    + "Tell the worker to take all units to the Packing Counter and reply 'done'. No further tool calls are needed.");
                                conversationHistory.add(packMsg);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Gate: the PO check can't run without a known arrival quantity. Ask the worker
            // directly rather than letting the model hallucinate a number.
            if (quantityMissing) {
                String productLabel = lastArrivingProductId.isEmpty() ? "the product" : lastArrivingProductId;
                String reply = "How many units of " + productLabel + " arrived?";
                System.out.println("\nZai: " + reply);
                JsonObject aMsg = new JsonObject();
                aMsg.addProperty("role", "assistant");
                aMsg.addProperty("content", reply);
                conversationHistory.add(aMsg);
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
            System.out.println("\nZai: " + content);
            conversationHistory.add(message);
        }
    }

    private static String dispatchTool(String funcName, JsonObject args) {
        try {
            switch (funcName) {
                case "readLocalPurchaseOrder":
                    if (lastArrivingQuantity <= 0) {
                        return TOOL_ERROR_PREFIX +
                               " QUANTITY_MISSING. Do not check the PO yet. Ask the worker how many units arrived first.";
                    }
                    poCheckDone = true;
                    return LocalFileTools.readLocalPurchaseOrder(
                            args.has("filePath") ? args.get("filePath").getAsString() : "PO_April20.csv");

                case "getProductAnalysis":
                    lastArrivingProductId = args.get("productId").getAsString().toUpperCase();
                    System.out.println("[SYSTEM] AI resolved product ID: " + lastArrivingProductId);
                    return WarehouseSkills.getProductAnalysis(args.get("productId").getAsString());

                case "findOptimalBin":
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
                               " PO_CHECK_REQUIRED. Call readLocalPurchaseOrder before finding a bin.";
                    }
                    // Java computes weight/volume/velocity itself from the DB so the model can't
                    // mis-multiply or echo stale numbers. The args passed in are ignored.
                    double[] dims = WarehouseSkills.getProductDimensions(lastArrivingProductId);
                    if (dims == null) {
                        return TOOL_ERROR_PREFIX + " Product '" + lastArrivingProductId + "' not found in database.";
                    }
                    double totalWeight = dims[0] * lastArrivingQuantity;
                    double totalVolume = dims[1] * lastArrivingQuantity;
                    int    velocity    = (int) Math.round(dims[2]);
                    System.out.println("[SYSTEM] computed totals: " + lastArrivingQuantity + " x "
                            + lastArrivingProductId + " => " + totalWeight + "kg, " + totalVolume
                            + "m3, velocity=" + velocity);
                    return WarehouseSkills.findOptimalBin(totalWeight, totalVolume, velocity, lastArrivingProductId);

                case "listWarehouseInventory":
                    return WarehouseSkills.listWarehouseInventory();

                case "findProductLocation":
                    return WarehouseSkills.findProductLocation(
                            args.get("productId").getAsString().toUpperCase());

                case "searchProductByDescription":
                    String searchResult = WarehouseSkills.searchProductByDescription(args.get("keyword").getAsString());
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
                    if (!workerJustConfirmed()) {
                        return TOOL_ERROR_PREFIX +
                               " updateBinStatus BLOCKED. Worker has not confirmed placement. " +
                               "Tell the worker the bin location and wait for 'done'.";
                    }
                    String binResult = InventoryTools.updateBinStatus(
                            args.get("binId").getAsString(),
                            args.get("status").getAsString(),
                            args.get("productId").getAsString());
                    if (!binResult.startsWith(TOOL_ERROR_PREFIX)) {
                        lastArrivingProductId  = "";
                        lastArrivingQuantity   = -1;
                        poCheckDone            = false;
                        allToPackingCounter    = false;
                        awaitingArrivalProduct = false;
                        pendingBareProductId   = "";
                    }
                    return binResult;

                case "reportAccident":
                    String accidentResult = WarehouseSkills.reportAccident(
                            args.get("location").getAsString(),
                            args.get("description").getAsString());
                    if (!accidentResult.startsWith(TOOL_ERROR_PREFIX)) {
                        lastArrivingProductId = "";
                        lastArrivingQuantity = -1;
                        poCheckDone = false;
                    }
                    return accidentResult;

                case "clearAisle":
                    return WarehouseSkills.clearAisle(args.get("location").getAsString());

                default:
                    return TOOL_ERROR_PREFIX + " Unknown tool '" + funcName + "'";
            }
        } catch (Exception e) {
            return TOOL_ERROR_PREFIX + " Exception in '" + funcName + "': " + e.getMessage();
        }
    }

    private static final Pattern BIN_ID_PATTERN = Pattern.compile("[A-Z]\\d+-S\\d+-L\\d+-B\\d+");

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

    private static boolean workerJustConfirmed() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if ("user".equals(msg.get("role").getAsString())) {
                String content = msg.get("content").getAsString().toLowerCase();
                if (content.startsWith("[system")) continue; // skip injected PO check messages
                return content.contains("done")
                    || content.contains("placed")
                    || content.contains("confirmed")
                    || content.contains("ok")
                    || content.contains("yes");
            }
        }
        return false;
    }

    private static final String[] GREETING_TOKENS = {
        "hi", "hello", "hey", "morning", "afternoon", "evening", "yo", "sup", "howdy", "hiya", "greetings"
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

            tryUpdateArrivingQuantity(userInput);

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
                    String productLabel = pid;
                    if (analysis.startsWith("Product: ")) {
                        int comma = analysis.indexOf(",");
                        if (comma > 9) productLabel = analysis.substring(9, comma).trim();
                    }
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
                            String detail = WarehouseSkills.getProductAnalysis(sFirstId);
                            String productLabel = sFirstId;
                            if (detail.startsWith("Product: ")) {
                                int comma = detail.indexOf(",");
                                if (comma > 9) productLabel = detail.substring(9, comma).trim();
                            }
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
                        lastArrivingProductId = pid;
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
                    String reply = "Delivery complete. All units have been sent to the Packing Counter. Good work!";
                    System.out.println("\nZai: " + reply);
                    JsonObject uMsg = new JsonObject(); uMsg.addProperty("role", "user"); uMsg.addProperty("content", userInput); conversationHistory.add(uMsg);
                    JsonObject aMsg = new JsonObject(); aMsg.addProperty("role", "assistant"); aMsg.addProperty("content", reply); conversationHistory.add(aMsg);
                    lastArrivingProductId = "";
                    lastArrivingQuantity  = -1;
                    poCheckDone           = false;
                    allToPackingCounter   = false;
                    awaitingArrivalProduct = false;
                    pendingBareProductId  = "";
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

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userInput);
            conversationHistory.add(userMsg);

            System.out.println("[Zai is thinking...]");
            processAIResponse(callAPI(conversationHistory), 0);
        }

        scanner.close();
    }
}