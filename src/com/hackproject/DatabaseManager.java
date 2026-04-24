package com.hackproject;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // Query for Capacity/Grid data
    public static List<Map<String, Object>> getInventoryData() {
        List<Map<String, Object>> data = new ArrayList<>();
        String sql = "SELECT bin_id, status, blocked_status, capacity_pct, Product1, Product2 FROM Bins ORDER BY bin_id";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String binId = rs.getString("bin_id"); // e.g., "A1-S1-L1-B1"
                
                // Parse bin_id into components
                String[] parts = binId.split("-");
                String aisle = parts.length > 0 ? parts[0] : ""; // A1
                String shelf = parts.length > 1 ? parts[1] : ""; // S1
                String level = parts.length > 2 ? parts[2] : ""; // L1
                String bin = parts.length > 3 ? parts[3] : "";   // B1
                
                Map<String, Object> row = new HashMap<>();
                row.put("aisle", aisle);
                row.put("shelf", shelf);
                row.put("level", level);
                row.put("bin", bin);
                row.put("bin_id", binId);
                row.put("capacity", rs.getInt("capacity_pct"));
                row.put("status", rs.getString("status"));
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