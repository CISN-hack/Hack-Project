package com.hackproject;

import io.javalin.Javalin;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class WarehouseController {
    public static void main(String[] args) {
        Gson gson = new Gson();

        // 1. Start the server with proper CORS for React
        Javalin app = Javalin.create(config -> {
            config.plugins.enableCors(cors -> {
                cors.add(it -> it.anyHost()); 
            });
        }).start(8080);

<<<<<<< HEAD
        // 2. Inventory Data (for Grid & Capacity tabs)
=======
        // This replaces your @PostMapping("/chat")
        app.post("/api/chat", ctx -> {
            // 1. Get the message from Frontend
            Map<String, String> request = gson.fromJson(ctx.body(), new TypeToken<Map<String, String>>(){}.getType());
            String userMessage = request.get("message");

            System.out.println("Received message from Frontend: " + userMessage);

            // 2. Get AI Response
            String aiReply = AIAgent.getAIResponseForWeb(userMessage);

            // 3. Send back as JSON
            ctx.json(Map.of("reply", aiReply));
        });
        
        // This sends the bin occupancy data to your "Grid" and "Capacity" tabs
>>>>>>> 5422cc5121f3cfaa282ae9e7d4301b42aa9425e8
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