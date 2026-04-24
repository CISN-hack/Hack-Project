package com.hackproject;

import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import io.javalin.http.staticfiles.Location;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class WarehouseController {
    public static void main(String[] args) {
        Gson gson = new Gson();

        // Cloud Run injects PORT; fall back to 8080 for local dev.
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try { port = Integer.parseInt(portEnv.trim()); }
            catch (NumberFormatException ignored) {}
        }

        // Where the built React app lives in the container (see Dockerfile).
        // Override locally with STATIC_DIR if you want to serve a dev build.
        String staticDir = System.getenv().getOrDefault("STATIC_DIR", "/app/public");

        // 1. Start the server with proper CORS for React
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson()); // Tells Javalin how to read JSON
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });
            if (new java.io.File(staticDir).isDirectory()) {
                config.staticFiles.add(staticFiles -> {
                    staticFiles.directory = staticDir;
                    staticFiles.location  = Location.EXTERNAL;
                    staticFiles.hostedPath = "/";
                });
                // SPA fallback — any non-/api/* path that isn't a static asset serves index.html.
                config.spaRoot.addFile("/", staticDir + "/index.html", Location.EXTERNAL);
            } else {
                System.out.println("[static] " + staticDir + " not present — serving API only.");
            }
        }).start(port);

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