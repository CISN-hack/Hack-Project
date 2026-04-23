package com.hackproject;

import io.javalin.Javalin;
import java.util.Map;
import com.google.gson.Gson;

public class WarehouseController {
    public static void main(String[] args) {
        Gson gson = new Gson();

        // 1. Start the server with proper CORS for React
        Javalin app = Javalin.create(config -> {
            config.plugins.enableCors(cors -> {
                cors.add(it -> it.anyHost()); 
            });
        }).start(8080);

        // 2. Inventory Data (for Grid & Capacity tabs)
        app.get("/api/inventory", ctx -> {
            ctx.json(DatabaseManager.getInventoryData());
        });

        // 3. Sales Velocity Data (for Pulse/Velocity tab)
        app.get("/api/velocity", ctx -> {
            ctx.json(DatabaseManager.getSalesVelocity());
        });

        // 4. AI Chat Endpoint
        app.post("/api/chat", ctx -> {
            Map<String, String> request = gson.fromJson(ctx.body(), Map.class);
            String userMessage = request.get("message");

            System.out.println("Processing message: " + userMessage);

            // Get response from your AIAgent class
            String aiReply = AIAgent.getAIResponseForWeb(userMessage);

            ctx.json(Map.of("reply", aiReply));
        });

        System.out.println("🚀 Zai Server is live on http://localhost:8080");
    }
}