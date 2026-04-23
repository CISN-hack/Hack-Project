package com.hackproject;

import io.javalin.Javalin;
import java.util.Map;
import com.google.gson.Gson;

public class WarehouseController {
    public static void main(String[] args) {
        Gson gson = new Gson();

        // Starts the server on port 8080
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });
        }).start(8080);

        // This replaces your @PostMapping("/chat")
        app.post("/api/chat", ctx -> {
            // 1. Get the message from Frontend
            Map<String, String> request = gson.fromJson(ctx.body(), Map.class);
            String userMessage = request.get("message");

            System.out.println("Received message from Frontend: " + userMessage);

            // 2. Get AI Response
            String aiReply = AIAgent.getAIResponseForWeb(userMessage);

            // 3. Send back as JSON
            ctx.json(Map.of("reply", aiReply));
        });
        
        // This sends the bin occupancy data to your "Grid" and "Capacity" tabs
        app.get("/api/inventory", ctx -> {
            ctx.json(DatabaseManager.getInventoryData());
        });

        // This sends the product sales data to your "Pulse" (Sales) tab
        app.get("/api/velocity", ctx -> {
            ctx.json(DatabaseManager.getSalesVelocity());
        });

        System.out.println("Zai Server is running on http://localhost:8080");
    }
}