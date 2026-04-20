import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AIAgent {

    // --- ZHIPU AI CONFIGURATION ---
    
    private static final String API_KEY = "YOUR_ZHIPU_API_KEY_HERE"; 
    private static final String MODEL_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    private static final String SYSTEM_INSTRUCTION = 
        "You are Zai, a Warehouse System of Intelligence. " +
        "PROTOCOL ALPHA: If a worker mentions a delivery or incoming stock, you MUST first call 'readLocalPurchaseOrder' for 'PO_April20.csv'. " +
        "After checking the PO, use 'getProductAnalysis' to check weight and sales velocity. " +
        "Finally, use 'findOptimalBin' to suggest a storage location. Be concise and professional.";

    private static String getToolsDefinition() {
        try {
            return Files.readString(Paths.get("tools.json"));
        } catch (Exception e) {
            System.out.println("CRITICAL: Could not find tools.json!");
            return "[]";
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("==============================================");
        System.out.println("  ZAI (POWERED BY ZHIPU AI GLM-4) ONLINE      ");
        System.out.println("==============================================\n");

        while (true) {
            System.out.print("Worker: ");
            String userInput = scanner.nextLine();
            if (userInput.equalsIgnoreCase("exit")) break;

            System.out.println("[Zai is orchestrating via Zhipu AI...]");
            String response = callZhipu(userInput);
            processAIResponse(response);
        }
        scanner.close();
    }

    // --- STEP 1: TALK TO ZHIPU AI ---
    public static String callZhipu(String prompt) {
        String safePrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");

        // Zhipu uses standard 'messages' and 'model' parameters
        String payload = """
            {
              "model": "glm-4",
              "messages": [
                { "role": "system", "content": "%s" },
                { "role": "user", "content": "%s" }
              ],
              "tools": %s
            }
            """.formatted(SYSTEM_INSTRUCTION, safePrompt, getToolsDefinition());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY) // Zhipu requires Bearer Auth
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // --- STEP 2: PARSE ZHIPU RESPONSE ---
    public static void processAIResponse(String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            
            // Catch Zhipu API errors
            if (root.has("error")) {
                System.out.println("ZHIPU API ERROR: " + root.getAsJsonObject("error").get("message").getAsString());
                return;
            }

            JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");

            // Check if Zhipu wants to use a Tool
            if (message.has("tool_calls")) {
                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                
                for (JsonElement toolElement : toolCalls) {
                    JsonObject function = toolElement.getAsJsonObject().getAsJsonObject("function");
                    String funcName = function.get("name").getAsString();
                    
                    // Zhipu passes arguments as a JSON String, so we parse it
                    JsonObject args = JsonParser.parseString(function.get("arguments").getAsString()).getAsJsonObject();

                    System.out.println("\n[SYSTEM] Zai activating skill: " + funcName);
                    
                    String toolOutput = "";
                    if (funcName.equals("readLocalPurchaseOrder")) {
                        toolOutput = LocalFileTools.readLocalPurchaseOrder(args.has("filePath") ? args.get("filePath").getAsString() : "PO_April20.csv");
                    } else if (funcName.equals("getProductAnalysis")) {
                        toolOutput = WarehouseSkills.getProductAnalysis(args.get("productId").getAsString());
                    } else if (funcName.equals("findOptimalBin")) {
                        toolOutput = WarehouseSkills.findOptimalBin(
                            args.get("weight").getAsDouble(),
                            args.get("volume").getAsDouble(),
                            args.get("velocity").getAsInt()
                        );
                    }
                    
                    System.out.println("[DATA RETRIEVED]\n" + toolOutput);
                    System.out.println("[Zai is analyzing the new data...]");
                    
                    // Autonomous Loop Fix for Zhipu
                    String followUpPrompt = "System Data Injection: The tool '" + funcName + "' just returned this data:\n" + toolOutput + 
                                            "\nBased on this, what is the next step? Tell the worker exactly what to do.";
                    
                    String nextResponse = callZhipu(followUpPrompt);
                    processAIResponse(nextResponse); 
                }
            } else if (message.has("content")) {
                // Regular text reply
                System.out.println("\nZai: " + message.get("content").getAsString());
            }
        } catch (Exception e) {
            System.out.println("Parsing Error: Make sure your API key is correct.");
            System.out.println("Raw Response: " + jsonResponse);
        }
    }
}