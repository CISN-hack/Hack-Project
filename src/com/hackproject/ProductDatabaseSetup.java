package com.hackproject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class ProductDatabaseSetup {

    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        createProductsTable();
        insertProductData();
    }

    public static void createProductsTable() {
        // Adding the product_description column as requested
        String sql = "CREATE TABLE IF NOT EXISTS Products (\n"
                + " product_id TEXT PRIMARY KEY,\n"
                + " product_name TEXT NOT NULL,\n"
                + " weight_kg REAL,\n"
                + " volume_m3 REAL,\n"
                + " product_description TEXT\n"
                + ");";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            System.out.println("Success: Products table created.");
        } catch (SQLException e) {
            System.out.println("Failed to create table: " + e.getMessage());
        }
    }

    public static void insertProductData() {
        String sql = "INSERT OR IGNORE INTO Products (product_id, product_name, weight_kg, volume_m3, product_description) VALUES (?, ?, ?, ?, ?)";

        // A 2D array holding all 20 products
        Object[][] products = {
            // KDK Fans
            {"K15VC", "60 inch 3-Blade Ceiling Fan", 6.5, 0.045, "KDK Regulator type ceiling fan, standard white finish, residential use."},
            {"K12V0", "48 inch 3-Blade Compact Fan", 5.2, 0.035, "KDK compact ceiling fan, standard white finish, ideal for small spaces."},
            {"K12W0", "48 inch 3-Blade Ceiling Fan", 5.2, 0.035, "KDK ceiling fan, copper brown finish, residential use."},
            {"K15YC", "60 inch 5-Blade DC Fan", 7.0, 0.050, "KDK DC motor ceiling fan, pearl white, energy-saving, premium series."},
            {"K14XZ-GY", "56 inch 4-Blade Ceiling Fan", 6.8, 0.048, "KDK ceiling fan, grey finish, includes remote control."},
            {"KU50Y", "20 inch Industrial Wall Fan", 8.5, 0.080, "KDK heavy-duty industrial wall fan, aluminum blades, silver, high velocity."},
            {"KU40Y", "16 inch Industrial Wall Fan", 6.5, 0.060, "KDK industrial wall fan, aluminum blades, silver, commercial warehouse use."},

            // UMS Fans
            {"UMS16W", "16 inch Wall Fan", 3.5, 0.040, "UMS residential wall fan, 3-speed string pull control, white plastic body."},
            {"UMS18C", "18 inch Commercial Wall Fan", 5.0, 0.055, "UMS commercial grade wall fan, black finish, heavy-duty motor."},

            // Philips Lighting
            {"PHL-9W-D", "Philips LED 9W Bulb", 0.08, 0.0004, "Philips Essential LED bulb, E27 base, Cool Daylight (6500K), single pack."},
            {"PHL-13W-W", "Philips LED 13W Bulb", 0.10, 0.0005, "Philips Essential LED bulb, E27 base, Warm White (3000K), single pack."},
            
            // Amber Light Bars
            {"AMB-LB48", "48 inch Amber Light Bar", 3.5, 0.025, "LED Amber warning light bar, 12V/24V, industrial safety vehicle use."},
            {"AMB-LB24", "24 inch Amber Light Bar", 1.8, 0.012, "LED Amber mini warning light bar, magnetic mount, high visibility."},

            // Joven Water Heaters
            {"JOV-JH91", "Joven 91L Storage Water Heater", 28.5, 0.185, "Joven large capacity horizontal storage water heater, extremely heavy, requires heavy-duty shelving."},
            {"JOV-JH25", "Joven 25L Storage Water Heater", 12.0, 0.090, "Joven medium horizontal storage water heater, suitable for residential bathrooms."},
            {"JOV-SA10", "Joven Instant Water Heater", 3.0, 0.015, "Joven SA series instant water heater with pump, white, standard packaging."},
            
            // Schneider Switches & Electrical
            {"SCH-E8331", "Schneider AvatarOn 1-Gang Switch", 0.1, 0.0005, "Schneider Electric AvatarOn series, 1-Gang 1-Way switch, white, very small box."},
            {"SCH-E8332", "Schneider AvatarOn 2-Gang Switch", 0.1, 0.0005, "Schneider Electric AvatarOn series, 2-Gang 1-Way switch, white, very small box."},
            {"SCH-E3000", "Schneider Vivace 13A Socket", 0.15, 0.0006, "Schneider Electric Vivace 13A switched wall socket, standard white."},
            {"SCH-DB12", "Schneider 12-Way Distribution Board", 2.5, 0.012, "Schneider Electric metal clad distribution board (MCB box), 12-way, sturdy packaging."}
        };

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int insertedCount = 0;

            // Loop through the array and insert each product
            for (Object[] product : products) {
                pstmt.setString(1, (String) product[0]); // product_id
                pstmt.setString(2, (String) product[1]); // product_name
                pstmt.setDouble(3, (Double) product[2]); // weight_kg
                pstmt.setDouble(4, (Double) product[3]); // volume_m3
                pstmt.setString(5, (String) product[4]); // product_description
                
                pstmt.executeUpdate();
                insertedCount++;
            }

            System.out.println("Success: " + insertedCount + " products inserted into the database.");

        } catch (SQLException e) {
            System.out.println("Failed to insert products: " + e.getMessage());
        }
    }
}
