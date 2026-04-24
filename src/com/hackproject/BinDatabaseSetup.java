package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class BinDatabaseSetup {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        createTable();
        insertMockData();
    }

    public static void createTable() {
        String dropSql = "DROP TABLE IF EXISTS Bins;";

        String createSql = "CREATE TABLE Bins (" +
                           "bin_id TEXT PRIMARY KEY, " +
                           "status TEXT CHECK(status IN ('Empty', 'Half', 'Full')), " +
                           "blocked_status TEXT CHECK(blocked_status IN ('Blocked', 'Clear')) DEFAULT 'Clear', " +
                           "max_weight_capacity REAL, " +
                           "max_volume_m3 REAL, " +
                           "accessibility_score INTEGER, " +
                           "Product1 TEXT, " +
                           "Product1_qty INTEGER DEFAULT 0, " +
                           "Product2 TEXT, " +
                           "Product2_qty INTEGER DEFAULT 0, " +
                           "capacity_pct INTEGER DEFAULT 0" +
                           ");";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute(dropSql);
            stmt.execute(createSql);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bins_product1 ON Bins(Product1);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bins_product2 ON Bins(Product2);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bins_status   ON Bins(status, blocked_status);");
            System.out.println("Table created: status ('Empty','Half','Full') + blocked_status ('Blocked','Clear').");

        } catch (SQLException e) {
            System.out.println("Failed to create table: " + e.getMessage());
        }
    }

    public static void insertMockData() {
        String sql = "INSERT INTO Bins (bin_id, status, blocked_status, max_weight_capacity, max_volume_m3, accessibility_score, Product1, Product1_qty, Product2, Product2_qty, capacity_pct) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int totalBinsCreated = 0;

            for (int aisle = 1; aisle <= 3; aisle++) {
                for (int shelf = 1; shelf <= 2; shelf++) {
                    for (int level = 1; level <= 3; level++) {
                        for (int bin = 1; bin <= 3; bin++) {

                            String binId = "A" + aisle + "-S" + shelf + "-L" + level + "-B" + bin;
                            String status = "Empty";
                            String blockedStatus = "Clear"; // All bins start as Clear

                            double maxWeight = 0;
                            double maxVolume = 0;
                            int accessibilityScore = 0;

                            switch (level) {
                                case 1: maxWeight = 500.0; maxVolume = 5; accessibilityScore = 5; break;
                                case 2: maxWeight = 250.0; maxVolume = 3; accessibilityScore = 3; break;
                                case 3: maxWeight = 100.0; maxVolume = 1; accessibilityScore = 1; break;
                            }

                            pstmt.setString(1, binId);
                            pstmt.setString(2, status);
                            pstmt.setString(3, blockedStatus); // <-- New column
                            pstmt.setDouble(4, maxWeight);
                            pstmt.setDouble(5, maxVolume);
                            pstmt.setInt(6, accessibilityScore);
                            pstmt.setString(7, null);
                            pstmt.setInt(8, 0);
                            pstmt.setString(9, null);
                            pstmt.setInt(10, 0);
                            pstmt.setInt(11, 0);

                            pstmt.executeUpdate();
                            totalBinsCreated++;
                        }
                    }
                }
            }

            System.out.println("Success: " + totalBinsCreated + " bins initialized.");

        } catch (SQLException e) {
            System.out.println("Failed to insert mock data: " + e.getMessage());
        }
    }
}


