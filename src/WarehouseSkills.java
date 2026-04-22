import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.google.gson.JsonObject;
import java.io.IOException;

public class WarehouseSkills {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // ── Telegram ──────────────────────────────────────────────────────────────
    private static final String BOT_TOKEN = "8636072818:AAG4o-SaBaFss43B55lZDluadOtLF8jVsjM";
    // Add or remove chat IDs here to manage the manager notification list
    private static final String[] MANAGER_CHAT_IDS = {
        "1324772413",  // Manager 1
        "5245006618"   // Manager 2
    };

    public static String getProductAnalysis(String productId) {
        String sql = "SELECT p.*, (SELECT SUM(quantity) FROM Sales WHERE product_id = p.product_id) as velocity " +
                     "FROM Products p WHERE p.product_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, productId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return String.format("Product: %s, Weight: %.2fkg, Volume: %.2fm3, Velocity: %d",
                       rs.getString("product_name"),
                       rs.getDouble("weight_kg"),
                       rs.getDouble("volume_m3"),
                       rs.getInt("velocity"));
            }
        } catch (SQLException e) { return "DB Error: " + e.getMessage(); }
        return "Product not found.";
    }

    public static String findOptimalBin(double weight, double volume, int velocity, String productId) {
        int targetAccess;
        if (velocity >= 80) targetAccess = 5;
        else if (velocity >= 30) targetAccess = 3;
        else targetAccess = 1;

        String sql = "SELECT bin_id FROM Bins " +
                     "WHERE status IN ('Empty', 'Half') " +
                     "AND blocked_status = 'Clear' " +
                     "AND (CASE WHEN status = 'Half' THEN max_weight_capacity * 0.5 ELSE max_weight_capacity END) >= ? " +
                     "AND (CASE WHEN status = 'Half' THEN max_volume_m3 * 0.5 ELSE max_volume_m3 END) >= ? " +
                     "ORDER BY " +
                     "  CASE WHEN Product1 = ? OR Product2 = ? THEN 0 ELSE 1 END, " +
                     "  CASE WHEN accessibility_score >= ? THEN 0 ELSE 1 END, " +
                     "  CASE WHEN status = 'Half' THEN 1 ELSE 2 END, " +
                     "  accessibility_score ASC " +
                     "LIMIT 1";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, weight);
            pstmt.setDouble(2, volume);
            pstmt.setString(3, productId);
            pstmt.setString(4, productId);
            pstmt.setInt(5, targetAccess);

            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getString("bin_id") : "CRITICAL ALERT: Warehouse is physically full! No bins available.";

        } catch (SQLException e) {
            return "Database search error: " + e.getMessage();
        }
    }

    public static String searchProductByDescription(String keyword) {
        String query = "SELECT product_id, product_name FROM products WHERE product_name LIKE ? OR product_description LIKE ? LIMIT 5";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, "%" + keyword + "%");
            pstmt.setString(2, "%" + keyword + "%");
            ResultSet rs = pstmt.executeQuery();

            StringBuilder results = new StringBuilder("Search Results:\n");
            boolean found = false;
            while (rs.next()) {
                found = true;
                results.append("- ID: ").append(rs.getString("product_id"))
                       .append(" | Name: ").append(rs.getString("product_name")).append("\n");
            }
            return found ? results.toString() : "No products found matching: " + keyword;

        } catch (Exception e) {
            return "Database search error: " + e.getMessage();
        }
    }

    // Handles only notification — DB blocking is delegated to InventoryTools
    public static String reportAccident(String location, String description) {
        int binsBlocked = InventoryTools.blockBinsByLocation(location);

        String alertMessage = "<b>🚨 WAREHOUSE ALERT 🚨</b>\n" +
                              "📍 <b>Location:</b> " + escapeHtml(location) + "\n" +
                              "⚠️ <b>Issue:</b> " + escapeHtml(description) + "\n" +
                              "🔒 <b>Action:</b> " + binsBlocked + " bins blocked. Zai is rerouting traffic.";

        String error = notifyAllManagers(alertMessage);
        if (error != null) return "Accident logged, bins blocked, but Telegram failed: " + error;

        return "SUCCESS: Area '" + location + "' marked as blocked (" + binsBlocked + " bins locked). All managers notified via Telegram.";
    }

    // Handles only notification — DB clearing is delegated to InventoryTools
    public static String clearAisle(String location) {
        int binsCleared = InventoryTools.clearBinsByLocation(location);

        String alertMessage = "<b>✅ AISLE CLEARED</b>\n" +
                              "📍 <b>Location:</b> " + escapeHtml(location) + "\n" +
                              "🔓 <b>Action:</b> " + binsCleared + " bins unblocked. Zai has restored routing to the area.";

        String error = notifyAllManagers(alertMessage);
        if (error != null) return "Aisle cleared in database, but Telegram failed: " + error;

        return "SUCCESS: Area '" + location + "' is now clear (" + binsCleared + " bins unblocked). All managers notified via Telegram.";
    }

    // Sends an HTML Telegram message to every manager in MANAGER_CHAT_IDS.
    // Returns null on full success, or the first error string encountered.
    private static String notifyAllManagers(String alertMessage) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (String chatId : MANAGER_CHAT_IDS) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("chat_id", chatId);
                payload.addProperty("text", alertMessage);
                payload.addProperty("parse_mode", "HTML");

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                java.net.http.HttpResponse<String> response = client.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());

                System.out.println("[TELEGRAM -> " + chatId + "] Status: " + response.statusCode());
                System.out.println("[TELEGRAM -> " + chatId + "] Body:   " + response.body());

                if (response.statusCode() != 200) {
                    return "chat " + chatId + " rejected with status " + response.statusCode()
                           + ": " + response.body();
                }
            } catch (IOException | InterruptedException e) {
                return "chat " + chatId + " exception: " + e.getMessage();
            }
        }
        return null;
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
