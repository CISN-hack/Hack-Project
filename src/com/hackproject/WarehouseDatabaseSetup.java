package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class WarehouseDatabaseSetup {

    // tells JDBC to create a local file named "warehouse_demo.db"
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        createNewDatabase();
        System.out.println("Next steps: run BinDatabaseSetup, ProductDatabaseSetup, SalesDatabaseSetup.");
    }

    public static void createNewDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            if (conn != null) {
                System.out.println("Success: A new database file has been created.");
            }
        } catch (SQLException e) {
            System.out.println("Database connection failed: " + e.getMessage());
        }
    }
}