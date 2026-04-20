

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class WarehouseDatabaseSetup {

    // tells JDBC to create a local file named "warehouse_demo.db"
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        createNewDatabase();
        createBinsTable();
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

    public static void createBinsTable() {
        // SQL command to structure the Digital Twin
        String sql = "CREATE TABLE IF NOT EXISTS Bins (\n"
                + " bin_id TEXT PRIMARY KEY,\n"
                + " status TEXT NOT NULL,\n"
                + " max_weight_capacity REAL,\n"
                + " max_volume_m3 REAL,\n" // <-- NEW COLUMN
                + " accessibility_score INTEGER\n"
                + ");";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute(sql);
            System.out.println("Success: The Bins table is ready for data.");
            
        } catch (SQLException e) {
            System.out.println("Failed to create table: " + e.getMessage());
        }
    }
}