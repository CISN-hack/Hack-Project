package com.hackproject;

import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class WarehouseController {
    public static void main(String[] args) {
        Gson gson = new Gson();

        // 1. Start the server with proper CORS for React
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson()); // Tells Javalin how to read JSON
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost()); 
            });
        }).start(8080);

        // 2. GET: Inventory Data (Capacity & Grid)
        app.get("/api/inventory", ctx -> {
            ctx.json(DatabaseManager.getInventoryData());
        });

        // 3. GET: Sales Velocity Data (Pulse/Velocity tab)
        app.get("/api/velocity", ctx -> {
            ctx.json(DatabaseManager.getSalesVelocity());
        });

        // 4. POST: AI Chat Endpoint (ONLY ONE DEFINITION)
        app.post("/api/chat", ctx -> {
            try {
                Map<String, String> request = gson.fromJson(ctx.body(), new TypeToken<Map<String, String>>(){}.getType());
                String userMessage = request.get("message");

                if (userMessage == null) {
                    throw new Exception("Message content was null!");
                }

                System.out.println("Processing message: " + userMessage);

                // Get response from your AIAgent class
                String aiReply = AIAgent.getAIResponseForWeb(userMessage);

                ctx.json(Map.of("reply", aiReply));
            } catch (Exception e) {
                e.printStackTrace();
                ctx.status(500).result("Server Error: " + e.getMessage());
            }
        });

        System.out.println("🚀 Zai Server is live on http://localhost:8080");
    }
}