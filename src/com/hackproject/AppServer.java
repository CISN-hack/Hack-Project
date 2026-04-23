package com.hackproject;

import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Map;

public class AppServer {
    public static void main(String[] args) {
        
        // 1. Create the Gson "Translator"
        Gson gson = new Gson();

        // 2. Setup the Server (We REMOVED the .start(8080) from here)
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson()); // Tells Javalin how to read JSON
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });
        });

        // 3. Define the Route
        app.post("/api/chat", ctx -> {
            try {
                Map<String, String> body = gson.fromJson(ctx.body(), new TypeToken<Map<String, String>>(){}.getType());
                String userMessage = body.get("message");

                if (userMessage == null) {
                    throw new Exception("Message content was null!");
                }

                System.out.println("Received: " + userMessage);

                // Get AI response
                String aiReply = AIAgent.getAIResponseForWeb(userMessage);

                ctx.json(Map.of("reply", aiReply));
            } catch (Exception e) {
                e.printStackTrace(); 
                ctx.status(500).result("Server Error: " + e.getMessage());
            }
        });

        // 4. START THE SERVER ONLY ONCE AT THE VERY END
        app.start(8080);
        System.out.println("Server is live! Your app can now talk to me.");
    }
}