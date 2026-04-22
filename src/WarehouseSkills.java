import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.gson.JsonObject;

public class WarehouseSkills {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

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

        // DB write delegated to InventoryTools
        int binsBlocked = InventoryTools.blockBinsByLocation(location);

        // Telegram notification
        String botToken = "8636072818:AAG4o-SaBaFss43B55lZDluadOtLF8jVsjM";
        String chatId = "1324772413";

        String alertMessage = "<b>🚨 WAREHOUSE ALERT 🚨</b>\n" +
                              "📍 <b>Location:</b> " + escapeHtml(location) + "\n" +
                              "⚠️ <b>Issue:</b> " + escapeHtml(description) + "\n" +
                              "🔒 <b>Action:</b> " + binsBlocked + " bins blocked. Zai is rerouting traffic.";

        try {
            JsonObject jsonPayload = new JsonObject();
            jsonPayload.addProperty("chat_id", chatId);
            jsonPayload.addProperty("text", alertMessage);
            jsonPayload.addProperty("parse_mode", "HTML");

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload.toString()))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                return "Telegram rejected the message. Status: " + response.statusCode()
                       + " | Response: " + response.body();
            }

        } catch (Exception e) {
            return "Accident logged, bins blocked, but failed to reach Telegram API: " + e.getMessage();
        }

        return "SUCCESS: Area '" + location + "' marked as blocked (" + binsBlocked + " bins locked). Manager notified via Telegram.";
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}