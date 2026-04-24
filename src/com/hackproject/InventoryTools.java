package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class InventoryTools {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static String updateBinStatus(String binId, String aiSuggestedStatus, String productId, int quantity) {
        String checkSql = "SELECT Product1, Product2 FROM Bins WHERE bin_id = ?";
        String fillSlot1Sql = "UPDATE Bins SET status = ?, Product1 = ?, Product1_qty = ? WHERE bin_id = ?";
        String fillSlot2Sql = "UPDATE Bins SET status = ?, Product2 = ?, Product2_qty = ? WHERE bin_id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {

            checkStmt.setString(1, binId);
            ResultSet rs = checkStmt.executeQuery();

            if (rs.next()) {
                String p1 = rs.getString("Product1");
                String p2 = rs.getString("Product2");

                boolean p1Empty = (p1 == null || p1.trim().isEmpty());
                boolean p2Empty = (p2 == null || p2.trim().isEmpty());

                String finalSql;
                String calculatedStatus;

                if (p1Empty) {
                    finalSql = fillSlot1Sql;
                    calculatedStatus = "Half";
                } else if (p2Empty) {
                    // 90% weight threshold: if Product1 already occupies ≥90% of bin
                    // weight capacity, the bin is considered full — no Product2 allowed.
                    String threshSql =
                        "SELECT b.max_weight_capacity, " +
                        "  COALESCE(p.weight_kg * b.Product1_qty, 0) AS used_weight " +
                        "FROM Bins b LEFT JOIN Products p ON b.Product1 = p.product_id " +
                        "WHERE b.bin_id = ?";
                    try (PreparedStatement threshStmt = conn.prepareStatement(threshSql)) {
                        threshStmt.setString(1, binId);
                        ResultSet threshRs = threshStmt.executeQuery();
                        if (threshRs.next()) {
                            double maxW  = threshRs.getDouble("max_weight_capacity");
                            double usedW = threshRs.getDouble("used_weight");
                            if (maxW > 0 && usedW >= maxW * 0.9) {
                                return "SYSTEM ERROR: Bin " + binId + " is at "
                                    + (int) Math.round(usedW / maxW * 100)
                                    + "% weight capacity — Product2 slot is not available.";
                            }
                        }
                    }
                    finalSql = fillSlot2Sql;
                    calculatedStatus = "Full";
                } else {
                    return "SYSTEM ERROR: Bin " + binId + " is already physically full!";
                }

                try (PreparedStatement updateStmt = conn.prepareStatement(finalSql)) {
                    updateStmt.setString(1, calculatedStatus);
                    updateStmt.setString(2, productId);
                    updateStmt.setInt(3, quantity);
                    updateStmt.setString(4, binId);

                    int rows = updateStmt.executeUpdate();
                    if (rows <= 0) return "Update failed.";
                    return "Success! Placed " + quantity + " unit(s) of " + productId
                        + " into " + binId + ". Status automatically set to " + calculatedStatus;
                }
            }
            return "Bin not found in database.";

        } catch (SQLException e) {
            return "Database update error: " + e.getMessage();
        }
    }

    // Fix 8: update capacity_pct for one or more bins in a single SQL statement.
    // Called after updateBinStatus (single bin) or after the Java commit loop (all bins at once).
    public static void refreshCapacityPct(java.util.List<String> binIds) {
        if (binIds == null || binIds.isEmpty()) return;
        StringBuilder sql = new StringBuilder(
            "UPDATE Bins SET capacity_pct = CAST(ROUND(" +
            "  (COALESCE((SELECT weight_kg FROM Products WHERE product_id = Product1), 0) * Product1_qty + " +
            "   COALESCE((SELECT weight_kg FROM Products WHERE product_id = Product2), 0) * Product2_qty) " +
            "  / max_weight_capacity * 100) AS INTEGER) WHERE bin_id IN (");
        for (int i = 0; i < binIds.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < binIds.size(); i++) pstmt.setString(i + 1, binIds.get(i));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[DB ERROR] refreshCapacityPct: " + e.getMessage());
        }
    }

    // Moved from WarehouseSkills — owns all blocked_status writes
    public static int blockBinsByLocation(String location) {
        String blockSql = "UPDATE Bins SET blocked_status = 'Blocked' WHERE bin_id LIKE ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(blockSql)) {

            String searchPrefix = location.replaceAll("[^0-9a-zA-Z]", "").toUpperCase();
            if (searchPrefix.contains("AISLE")) {
                searchPrefix = searchPrefix.replace("AISLE", "A");
            }
            pstmt.setString(1, searchPrefix + "%");
            return pstmt.executeUpdate(); // returns number of bins blocked

        } catch (Exception e) {
            System.out.println("[DB ERROR] Could not block aisle: " + e.getMessage());
            return 0;
        }
    }

    public static int clearBinsByLocation(String location) {
        String clearSql = "UPDATE Bins SET blocked_status = 'Clear' WHERE bin_id LIKE ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(clearSql)) {

            String searchPrefix = location.replaceAll("[^0-9a-zA-Z]", "").toUpperCase();
            if (searchPrefix.contains("AISLE")) {
                searchPrefix = searchPrefix.replace("AISLE", "A");
            }
            pstmt.setString(1, searchPrefix + "%");
            return pstmt.executeUpdate(); // returns number of bins unblocked

        } catch (Exception e) {
            System.out.println("[DB ERROR] Could not clear aisle: " + e.getMessage());
            return 0;
        }
    }
}