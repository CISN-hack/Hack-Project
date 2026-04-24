package com.hackproject;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // Query for Capacity/Grid data. bin_id format: A{aisle}-S{shelf}-L{level}-B{bin}
    public static List<Map<String, Object>> getInventoryData() {
        List<Map<String, Object>> data = new ArrayList<>();
        String sql = "SELECT bin_id, blocked_status, capacity_pct, Product1, Product2 FROM Bins";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String binId = rs.getString("bin_id");
                String[] parts = binId.split("-");
                if (parts.length != 4) continue;

                Map<String, Object> row = new HashMap<>();
                row.put("aisle", parts[0]);          // e.g. "A1"
                row.put("shelf", parts[1]);          // e.g. "S1"
                row.put("level", parts[2]);          // e.g. "L1"
                row.put("bin", parts[3]);            // e.g. "B1"
                row.put("capacity", rs.getInt("capacity_pct"));
                row.put("blocked_status", rs.getString("blocked_status"));
                row.put("product1", rs.getString("Product1"));
                row.put("product2", rs.getString("Product2"));
                data.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return data;
    }

    // Query for Velocity/Sales data. Growth = (recent half) vs (earlier half) of the Sales date range.
    public static List<Map<String, Object>> getSalesVelocity() {
    List<Map<String, Object>> data = new ArrayList<>();

    // Midpoint splits the Sales date range in two; products with more sales in
    // the recent half are trending up, fewer are trending down.
    String sql =
        "WITH mid AS (" +
        "  SELECT date(MIN(sale_date), '+' || " +
        "    CAST((julianday(MAX(sale_date)) - julianday(MIN(sale_date))) / 2 AS INTEGER) || " +
        "    ' days') AS mid_date FROM Sales" +
        ") " +
        "SELECT p.product_name AS name, " +
        "       SUM(s.quantity) AS velocity, " +
        "       SUM(CASE WHEN s.sale_date >  (SELECT mid_date FROM mid) THEN s.quantity ELSE 0 END) AS recent_qty, " +
        "       SUM(CASE WHEN s.sale_date <= (SELECT mid_date FROM mid) THEN s.quantity ELSE 0 END) AS prev_qty " +
        "FROM Sales s " +
        "JOIN Products p ON TRIM(UPPER(s.product_id)) = TRIM(UPPER(p.product_id)) " +
        "GROUP BY p.product_name " +
        "ORDER BY velocity DESC LIMIT 5";

    try (Connection conn = DriverManager.getConnection(DB_URL);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            String name = rs.getString("name");
            int velocity = rs.getInt("velocity");
            int recent = rs.getInt("recent_qty");
            int prev   = rs.getInt("prev_qty");

            String growth;
            if (prev == 0 && recent == 0) {
                growth = "0%";
            } else if (prev == 0) {
                growth = "+NEW";
            } else {
                int pct = (int) Math.round(((recent - prev) / (double) prev) * 100);
                growth = (pct >= 0 ? "+" : "") + pct + "%";
            }

            System.out.println("✅ BACKEND FOUND: " + name + " | Qty: " + velocity + " | Growth: " + growth);

            row.put("name", name);
            row.put("velocity", velocity);
            row.put("growth", growth);
            data.add(row);
        }
    } catch (SQLException e) {
        e.printStackTrace();
    }
    return data;
}
}