package com.hackproject;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // Query for Capacity/Grid data. bin_id format: A{aisle}-S{shelf}-L{level}-B{bin}
    public static List<Map<String, Object>> getInventoryData() {
        List<Map<String, Object>> data = new ArrayList<>();
        String sql = "SELECT bin_id, status, blocked_status FROM Bins";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String binId = rs.getString("bin_id");
                String[] parts = binId.split("-");
                if (parts.length != 4) continue;

                String occupancy = rs.getString("status");        // Empty | Half | Full
                String blocked = rs.getString("blocked_status");  // Blocked | Clear

                int capacity;
                switch (occupancy == null ? "" : occupancy) {
                    case "Full": capacity = 100; break;
                    case "Half": capacity = 50;  break;
                    default:     capacity = 0;   break;
                }

                Map<String, Object> row = new HashMap<>();
                row.put("aisle", parts[0]);
                row.put("shelf", parts[1]);
                row.put("level", parts[2]);
                row.put("bin",   parts[3]);
                row.put("capacity", capacity);
                row.put("status", "Blocked".equals(blocked) ? "OFF" : "active");
                data.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return data;
    }

    // Query for Velocity/Sales data
    public static List<Map<String, Object>> getSalesVelocity() {
    List<Map<String, Object>> data = new ArrayList<>();
    
    // Using UPPER and TRIM to ignore case and hidden spaces
    String sql = "SELECT p.product_name AS name, SUM(s.quantity) AS velocity " +
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
            int velocity = rs.getInt("velocity"); // Ensure this is getInt!
            
            // This will print to your JAVA terminal so we can see if data exists
            System.out.println("✅ BACKEND FOUND: " + name + " | Qty: " + velocity);
            
            row.put("name", name);
            row.put("velocity", velocity);
            row.put("growth", "+12%");
            data.add(row);
        }
    } catch (SQLException e) {
        e.printStackTrace();
    }
    return data;
}
}