package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

    // Full-warehouse inventory: every product currently in a bin, grouped with its bin list.
    public static String listWarehouseInventory() {
        String sql =
            "SELECT b.pid AS pid, p.product_name AS product_name, " +
            "       GROUP_CONCAT(b.bin_id, ', ') AS bins, COUNT(*) AS bin_count " +
            "FROM ( " +
            "  SELECT Product1 AS pid, bin_id FROM Bins WHERE Product1 IS NOT NULL AND Product1 != '' " +
            "  UNION ALL " +
            "  SELECT Product2 AS pid, bin_id FROM Bins WHERE Product2 IS NOT NULL AND Product2 != '' " +
            ") b " +
            "LEFT JOIN Products p ON p.product_id = b.pid " +
            "GROUP BY b.pid, p.product_name " +
            "ORDER BY p.product_name";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();
            StringBuilder sb = new StringBuilder();
            int products = 0;
            while (rs.next()) {
                products++;
                String pid = rs.getString("pid");
                String name = rs.getString("product_name");
                String bins = rs.getString("bins");
                int binCount = rs.getInt("bin_count");
                sb.append("- ").append(pid);
                if (name != null) sb.append(" (").append(name).append(")");
                sb.append(" — ").append(binCount).append(" bin(s): ").append(bins).append("\n");
            }
            return products > 0
                ? "Current warehouse inventory — " + products + " product(s):\n" + sb
                : "Warehouse is currently empty — no products stored in any bin.";
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }

    // Lists every bin currently holding the product in slot 1 or 2.
    public static String findProductLocation(String productId) {
        String sql = "SELECT bin_id, status, blocked_status FROM Bins " +
                     "WHERE Product1 = ? OR Product2 = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, productId);
            pstmt.setString(2, productId);
            ResultSet rs = pstmt.executeQuery();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (rs.next()) {
                count++;
                sb.append("- ").append(rs.getString("bin_id"))
                  .append(" (status: ").append(rs.getString("status"))
                  .append(", ").append(rs.getString("blocked_status")).append(")\n");
            }
            return count > 0
                ? "Product " + productId + " is stored in " + count + " bin(s):\n" + sb
                : "Product " + productId + " is not currently stored in any bin.";
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }

    // Returns [weight_kg_per_unit, volume_m3_per_unit, velocity] for a product, or null if not found.
    // Used by AIAgent to compute totals itself instead of trusting the model's multiplication.
    public static double[] getProductDimensions(String productId) {
        String sql = "SELECT p.weight_kg, p.volume_m3, " +
                     "(SELECT COALESCE(SUM(quantity), 0) FROM Sales WHERE product_id = p.product_id) AS velocity " +
                     "FROM Products p WHERE p.product_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, productId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new double[] {
                    rs.getDouble("weight_kg"),
                    rs.getDouble("volume_m3"),
                    rs.getDouble("velocity")
                };
            }
        } catch (SQLException ignored) {}
        return null;
    }

    // based on weight, volume, velocity, and product ID (for affinity), finds the single best bin for storing the item
    // based on the status of bins (half empty or fully empty), blocked_status (must be 'Clear'), and accessibility_score (must meet or exceed threshold based on velocity)
    // prioritizes bins that already contain the same product (Product1 or Product2) to optimize for picking efficiency
    // prioritiZes Half bins over Empty bins to maximize space utilization, but only if they meet the weight/volume requirements after accounting for existing contents
    public static String findOptimalBin(double unitWeight, double unitVolume, int velocity, String productId, int remainingQty, List<String> excludedBins) {
        int targetAccess;
        if (velocity >= 80) targetAccess = 5;
        else if (velocity >= 30) targetAccess = 3;
        else targetAccess = 1;

        String exclusionClause = "";
        if (excludedBins != null && !excludedBins.isEmpty()) {
            StringBuilder sb = new StringBuilder("AND b.bin_id NOT IN (");
            for (int i = 0; i < excludedBins.size(); i++) {
                sb.append(i == 0 ? "?" : ",?");
            }
            sb.append(") ");
            exclusionClause = sb.toString();
        }

        String sql =
            "SELECT b.bin_id, " +
            "  (b.max_weight_capacity - COALESCE(p1.weight_kg, 0) - COALESCE(p2.weight_kg, 0)) AS rem_weight, " +
            "  (b.max_volume_m3       - COALESCE(p1.volume_m3,  0) - COALESCE(p2.volume_m3,  0)) AS rem_volume " +
            "FROM Bins b " +
            "LEFT JOIN Products p1 ON b.Product1 = p1.product_id " +
            "LEFT JOIN Products p2 ON b.Product2 = p2.product_id " +
            "WHERE b.status IN ('Empty', 'Half') " +
            "AND b.blocked_status = 'Clear' " +
            "AND b.accessibility_score >= ? " +
            "AND (b.max_weight_capacity - COALESCE(p1.weight_kg, 0) - COALESCE(p2.weight_kg, 0)) >= ? " +
            "AND (b.max_volume_m3       - COALESCE(p1.volume_m3,  0) - COALESCE(p2.volume_m3,  0)) >= ? " +
            exclusionClause +
            "ORDER BY " +
            "  CASE WHEN b.status = 'Half' THEN 0 ELSE 1 END, " +
            "  CASE WHEN b.Product1 = ? OR b.Product2 = ? THEN 0 ELSE 1 END, " +
            "  b.accessibility_score ASC " +
            "LIMIT 1";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int p = 1;
            pstmt.setInt(p++, targetAccess);
            pstmt.setDouble(p++, unitWeight);
            pstmt.setDouble(p++, unitVolume);
            if (excludedBins != null) {
                for (String bin : excludedBins) pstmt.setString(p++, bin);
            }
            pstmt.setString(p++, productId);
            pstmt.setString(p,   productId);

            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                return "CRITICAL ALERT: Warehouse is physically full! No bins available.";
            }

            String binId     = rs.getString("bin_id");
            double remWeight = rs.getDouble("rem_weight");
            double remVolume = rs.getDouble("rem_volume");

            int fitByWeight = (int) Math.floor(remWeight / unitWeight);
            int fitByVolume = (int) Math.floor(remVolume / unitVolume);
            int unitsInBin  = Math.min(remainingQty, Math.min(fitByWeight, fitByVolume));
            int remainder   = remainingQty - unitsInBin;

            return "BINASSIGN|" + binId + "|" + unitsInBin + "|" + remainder;

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

    // Sends the end-of-shift report to all managers via Telegram.
    public static void notifyShiftReport(String plainReport) {
        String[] lines = plainReport.split("\n");
        StringBuilder html = new StringBuilder();
        for (String line : lines) {
            String escaped = escapeHtml(line);
            if (line.startsWith("📊") || line.startsWith("📦") || line.startsWith("🚨") || line.startsWith("✅")) {
                html.append("<b>").append(escaped).append("</b>\n");
            } else {
                html.append(escaped).append("\n");
            }
        }
        String error = notifyAllManagers(html.toString().trim());
        if (error != null) System.out.println("[SHIFT REPORT] Telegram failed: " + error);
        else System.out.println("[SHIFT REPORT] Sent to all managers.");
    }

    // Notifies all managers when a CO match is found for arriving stock.
    public static void notifyCoArrival(String productId, String customer, String coQty, String priority, int arrivedQty) {
        String message = "<b>📦 CUSTOMER ORDER ARRIVAL</b>\n" +
                         "🏷️ <b>Product:</b> " + escapeHtml(productId) + "\n" +
                         "🏢 <b>Customer:</b> " + escapeHtml(customer) + "\n" +
                         "🚚 <b>Arrived:</b> " + arrivedQty + " units\n" +
                         "⚡ <b>Priority:</b> " + escapeHtml(priority);
        String error = notifyAllManagers(message);
        if (error != null) System.out.println("[CO NOTIFY] Telegram failed: " + error);
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
