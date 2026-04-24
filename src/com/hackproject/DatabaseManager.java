package com.hackproject;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // Query for Capacity/Grid data
    public static List<Map<String, Object>> getInventoryData() {
        List<Map<String, Object>> data = new ArrayList<>();
        // FIX: Added Product1 and Product2 to the SELECT statement
        String sql = "SELECT bin_id, status, blocked_status, Product1, Product2 FROM Bins";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                String binId = rs.getString("bin_id");

                String[] parts = binId.split("-");
                if (parts.length == 4) {
                    row.put("aisle", parts[0].trim());
                    row.put("shelf", parts[1].trim());
                    row.put("level", parts[2].trim());
                    row.put("bin", parts[3].trim());
                }

                // These will now work because they are in the SELECT above
                row.put("product1", rs.getString("Product1")); 
                row.put("product2", rs.getString("Product2"));
                row.put("blocked_status", rs.getString("blocked_status")); 
                
                String currentStatus = rs.getString("status");
                int capacity = 0;
                if ("Half".equalsIgnoreCase(currentStatus)) capacity = 50;
                else if ("Full".equalsIgnoreCase(currentStatus)) capacity = 100;
                
                row.put("capacity", capacity);
                data.add(row);
            }
        } catch (SQLException e) { 
            e.printStackTrace(); 
        }
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