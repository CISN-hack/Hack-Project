package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class SalesDatabaseSetup {

    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        createSalesTable();
        insertSalesData();
    }

    public static void createSalesTable() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            // Drop old table to allow easy resetting
            stmt.execute("DROP TABLE IF EXISTS Sales;");

            // Create the Sales table. Note the FOREIGN KEY linking to Products.
            String sql = "CREATE TABLE Sales (\n"
                    + " sale_id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " sale_date TEXT NOT NULL,\n"
                    + " sale_time TEXT NOT NULL,\n"
                    + " product_id TEXT NOT NULL,\n"
                    + " quantity INTEGER NOT NULL,\n"
                    + " customer_name TEXT,\n"
                    + " FOREIGN KEY(product_id) REFERENCES Products(product_id)\n"
                    + ");";
            
            stmt.execute(sql);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sales_product_id ON Sales(product_id);");
            System.out.println("Success: Sales table created.");
            
        } catch (SQLException e) {
            System.out.println("Failed to create table: " + e.getMessage());
        }
    }

    public static void insertSalesData() {
        String sql = "INSERT INTO Sales (sale_date, sale_time, product_id, quantity, customer_name) VALUES (?, ?, ?, ?, ?)";

        // 30 carefully crafted records spanning 5 days
       // 50 carefully crafted records spanning a full 7-day week
        Object[][] salesData = {
            // Day 1 (April 15, 2026)
            {"2026-04-15", "08:15", "PHL-9W-D", 150, "Maju Builders Sdn Bhd"},
            {"2026-04-15", "08:30", "SCH-E8331", 200, "Maju Builders Sdn Bhd"},
            {"2026-04-15", "10:05", "K15VC", 12, "Tan Electric Shop"},
            {"2026-04-15", "11:30", "UMS16W", 5, "Ah Chong Hardware"},
            {"2026-04-15", "14:20", "JOV-SA10", 3, "Tan Electric Shop"},
            {"2026-04-15", "16:45", "PHL-13W-W", 100, "Steve"},
            {"2026-04-15", "17:15", "K14XZ-GY", 6, "Premium Home Deco"},

            // Day 2 (April 16, 2026)
            {"2026-04-16", "09:00", "JOV-JH91", 2, "Grand Hotel Project"}, // Heavy item, low velocity
            {"2026-04-16", "09:15", "SCH-DB12", 5, "Grand Hotel Project"},
            {"2026-04-16", "10:30", "K15YC", 8, "Premium Home Deco"},
            {"2026-04-16", "11:45", "SCH-E3000", 150, "Maju Builders Sdn Bhd"},
            {"2026-04-16", "13:10", "PHL-9W-D", 50, "Ah Chong Hardware"},
            {"2026-04-16", "15:25", "KU50Y", 4, "Cristine"}, // Industrial item
            {"2026-04-16", "16:00", "AMB-LB48", 2, "Mega Factory Supply"},

            // Day 3 (April 17, 2026)
            {"2026-04-17", "08:20", "SCH-E8332", 120, "KL Rapid Construction"},
            {"2026-04-17", "08:25", "SCH-E8331", 120, "KL Rapid Construction"},
            {"2026-04-17", "10:15", "K12V0", 15, "Tan Electric Shop"},
            {"2026-04-17", "12:00", "K12W0", 10, "Tan Electric Shop"},
            {"2026-04-17", "14:40", "UMS18C", 8, "City Office Renovations"},
            {"2026-04-17", "16:15", "PHL-9W-D", 200, "Maju Builders Sdn Bhd"},
            {"2026-04-17", "17:00", "SCH-E3000", 60, "Ah Chong Hardware"},

            // Day 4 (April 18, 2026)
            {"2026-04-18", "09:30", "JOV-JH25", 6, "Subang Jaya Plumbers"},
            {"2026-04-18", "11:00", "K14XZ-GY", 4, "Premium Home Deco"},
            {"2026-04-18", "13:20", "SCH-E3000", 80, "Ah Chong Hardware"},
            {"2026-04-18", "14:10", "AMB-LB24", 5, "Highway Maintenance Corp"},
            {"2026-04-18", "15:50", "JOV-SA10", 10, "Tan Electric Shop"},
            {"2026-04-18", "17:00", "PHL-13W-W", 80, "City Office Renovations"},
            {"2026-04-18", "17:30", "KU40Y", 3, "Mega Factory Supply"},

            // Day 5 (April 19, 2026)
            {"2026-04-19", "08:45", "KU40Y", 5, "Mega Factory Supply"},
            {"2026-04-19", "09:30", "K15VC", 20, "KL Rapid Construction"},
            {"2026-04-19", "11:15", "SCH-DB12", 10, "KL Rapid Construction"},
            {"2026-04-19", "14:05", "UMS16W", 15, "Tan Electric Shop"},
            {"2026-04-19", "15:00", "SCH-E8331", 150, "Maju Builders Sdn Bhd"},
            {"2026-04-19", "16:30", "PHL-9W-D", 300, "Maju Builders Sdn Bhd"},
            {"2026-04-19", "17:20", "K15YC", 5, "Premium Home Deco"},

            // Day 6 (April 20, 2026)
            {"2026-04-20", "08:30", "AMB-LB48", 3, "Highway Maintenance Corp"},
            {"2026-04-20", "10:00", "K12V0", 12, "Tan Electric Shop"},
            {"2026-04-20", "11:30", "PHL-9W-D", 180, "Maju Builders Sdn Bhd"},
            {"2026-04-20", "13:45", "SCH-E8332", 90, "KL Rapid Construction"},
            {"2026-04-20", "15:15", "JOV-JH91", 1, "Grand Hotel Project"}, // Extremely low velocity maintained
            {"2026-04-20", "16:20", "UMS18C", 6, "City Office Renovations"},
            {"2026-04-20", "17:05", "JOV-SA10", 5, "Tan Electric Shop"},

            // Day 7 (April 21, 2026)
            {"2026-04-21", "08:15", "PHL-13W-W", 120, "City Office Renovations"},
            {"2026-04-21", "09:45", "SCH-E3000", 200, "Maju Builders Sdn Bhd"},
            {"2026-04-21", "11:00", "K15VC", 15, "KL Rapid Construction"},
            {"2026-04-21", "12:30", "KU50Y", 2, "Mega Factory Supply"},
            {"2026-04-21", "14:10", "K12W0", 8, "Tan Electric Shop"},
            {"2026-04-21", "15:00", "SCH-DB12", 8, "KL Rapid Construction"},
            {"2026-04-21", "16:30", "JOV-JH25", 4, "Subang Jaya Plumbers"},
            {"2026-04-21", "17:45", "PHL-9W-D", 250, "Ah Chong Hardware"}
        };
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int insertedCount = 0;

            for (Object[] sale : salesData) {
                pstmt.setString(1, (String) sale[0]); // sale_date
                pstmt.setString(2, (String) sale[1]); // sale_time
                pstmt.setString(3, (String) sale[2]); // product_id
                pstmt.setInt(4, (Integer) sale[3]);   // quantity
                pstmt.setString(5, (String) sale[4]); // customer_name
                
                pstmt.executeUpdate();
                insertedCount++;
            }

            System.out.println("Success: " + insertedCount + " sales records inserted into the database.");

        } catch (SQLException e) {
            System.out.println("Failed to insert sales data: " + e.getMessage());
        }
    }
}
