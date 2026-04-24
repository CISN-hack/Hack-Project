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
    
    // This query groups sales by week and creates a "10,20,30" history string
    String sql = "SELECT p.product_name AS name, " +
                 "SUM(s.quantity) AS velocity, " +
                 "GROUP_CONCAT(s.quantity) AS history " + 
                 "FROM Sales s " +
                 "JOIN Products p ON TRIM(UPPER(s.product_id)) = TRIM(UPPER(p.product_id)) " +
                 "GROUP BY p.product_name " +
                 "ORDER BY velocity DESC LIMIT 5";

    try (Connection conn = DriverManager.getConnection(DB_URL);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", rs.getString("name"));
            row.put("velocity", rs.getInt("velocity"));
            // Send the history string to the frontend as 'sale_data'
            row.put("sale_data", rs.getString("history")); 
            data.add(row);
        }
    } catch (SQLException e) {
        e.printStackTrace();
    }
    return data;
}
}