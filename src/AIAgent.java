import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AIAgent {

    private static final String API_KEY   = "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";
    private static final String MODEL_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String MODEL_ID  = "meta/llama-3.3-70b-instruct";

    private static final String SYSTEM_INSTRUCTION =
        "You are Zai, a Warehouse System of Intelligence. " +
        "PROTOCOL ALPHA: If a worker mentions a delivery, call 'readLocalPurchaseOrder' first. " +
        "If the worker used a product name instead of ID, use 'searchProductByDescription'. " +
        "CROSS-DOCK: Compare incoming stock against PO demand. If it matches, tell worker to route the demanded quantity to 'Packing Counter' immediately. " +
        "If incoming ≤ PO demand, send ALL to packing, do not bin. " +
        "If incoming > PO or not on PO, bin the REMAINDER. " +
        "For binning: use 'getProductAnalysis', calculate total weight/volume for REMAINDER, then 'findOptimalBin'. " +
        "HOLD: After finding a bin, tell the worker the location and wait for them to reply 'done'. Do NOT call 'updateBinStatus' yet. " +
        "COMMIT: ONLY after worker confirms 'done', use 'updateBinStatus'. " +
        "Refuse new tasks until 'done' is confirmed.";

    private static final String TOOL_ERROR_PREFIX = "TOOL_DISPATCH_ERROR:";
    private static final JsonArray conversationHistory = new JsonArray();

    private static JsonArray getToolsJson() {
        try {
            return JsonParser.parseString(Files.readString(Paths.get("tools.json"))).getAsJsonArray();
        } catch (Exception e) {
            System.out.println("CRITICAL: tools.json not found!");
            return new JsonArray();
        }
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
                    System.out.println("[RATE LIMIT] Attempt " + attempt + "/3 — waiting " + (waitMs/1000) + "s...");
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
        if (root.has("status") && root.get("status").getAsInt() != 200) {
            System.out.println("[API ERROR " + root.get("status").getAsInt() + "]");
            return;
        }

        JsonObject message;
        try {
            message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        } catch (Exception e) {
            System.out.println("[ERROR] Unexpected response: " + jsonResponse);
            return;
        }

        if (message.has("tool_calls")) {

            if (depth >= MAX_DEPTH) {
                System.out.println("[WARNING] Max depth. Forcing text reply.");
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                processAIResponse(callAPI(conversationHistory, false), depth);
                return;
            }

            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            conversationHistory.add(message);

            boolean hadError = false;
            boolean ranFindBin = false;

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
                }

                if ("findOptimalBin".equals(funcName)) {
                    ranFindBin = true;
                }

                JsonObject toolResultMsg = new JsonObject();
                toolResultMsg.addProperty("role", "tool");
                toolResultMsg.addProperty("tool_call_id", toolCallId);
                toolResultMsg.addProperty("content", toolOutput);
                conversationHistory.add(toolResultMsg);
            }

            // CRITICAL FIX: After findOptimalBin runs, get ONE final text reply
            // and STOP. Don't recurse further. Worker must confirm before continuing.
            if (ranFindBin) {
                System.out.println("[Zai is preparing final instruction...]");
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                String finalResp = callAPI(conversationHistory, false); // force text
                processTextOnly(finalResp); // extract text, add to history, print, STOP
                return;
            }

            // For other tool chains or errors, continue normally
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
            System.out.println("\nZai: " + message.get("content").getAsString());
            conversationHistory.add(message);
        }
    }

    // Extract and display ONLY the text content, no further recursion.
    private static void processTextOnly(String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            JsonObject message = root.getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message");
            if (message.has("content") && !message.get("content").isJsonNull()) {
                System.out.println("\nZai: " + message.get("content").getAsString());
                conversationHistory.add(message);
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Could not parse final text response.");
        }
    }

    private static String dispatchTool(String funcName, JsonObject args) {
        try {
            switch (funcName) {
                case "readLocalPurchaseOrder":
                    return LocalFileTools.readLocalPurchaseOrder(
                            args.has("filePath") ? args.get("filePath").getAsString() : "PO_April20.csv");

                case "getProductAnalysis":
                    return WarehouseSkills.getProductAnalysis(args.get("productId").getAsString());

                case "findOptimalBin":
                    return WarehouseSkills.findOptimalBin(
                            args.get("weight").getAsDouble(),
                            args.get("volume").getAsDouble(),
                            args.get("velocity").getAsInt(),
                            args.get("productId").getAsString());

                case "searchProductByDescription":
                    return WarehouseSkills.searchProductByDescription(args.get("keyword").getAsString());

                case "updateBinStatus":
                    if (!workerJustConfirmed()) {
                        return TOOL_ERROR_PREFIX +
                               " updateBinStatus BLOCKED. Worker has not confirmed placement. " +
                               "Tell worker the bin location and wait for 'done'.";
                    }
                    return InventoryTools.updateBinStatus(
                            args.get("binId").getAsString(),
                            args.get("status").getAsString(),
                            args.get("productId").getAsString());

                default:
                    return TOOL_ERROR_PREFIX + " Unknown tool '" + funcName + "'";
            }
        } catch (Exception e) {
            return TOOL_ERROR_PREFIX + " Exception in '" + funcName + "': " + e.getMessage();
        }
    }

    private static boolean workerJustConfirmed() {
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            JsonObject msg = conversationHistory.get(i).getAsJsonObject();
            if ("user".equals(msg.get("role").getAsString())) {
                String content = msg.get("content").getAsString().toLowerCase();
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