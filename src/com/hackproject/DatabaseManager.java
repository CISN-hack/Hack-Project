package com.hackproject;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    // Query for Capacity/Grid data
    public static List<Map<String, Object>> getInventoryData() {
        List<Map<String, Object>> data = new ArrayList<>();
        String sql = "SELECT aisle, shelf, level, bin, capacity, status FROM inventory";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("aisle", rs.getString("aisle"));
                row.put("shelf", rs.getString("shelf"));
                row.put("level", rs.getString("level"));
                row.put("bin", rs.getString("bin"));
                row.put("capacity", rs.getInt("capacity"));
                row.put("status", rs.getString("status")); // e.g., 'active' or 'OFF'
                data.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return data;
    }

    // Query for Velocity/Sales data
    public static List<Map<String, Object>> getSalesVelocity() {
        List<Map<String, Object>> data = new ArrayList<>();
        String sql = "SELECT name, velocity, growth FROM products ORDER BY velocity DESC LIMIT 5";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("name", rs.getString("name"));
                row.put("velocity", rs.getInt("velocity"));
                row.put("growth", rs.getString("growth"));
                data.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return data;
    }
}