import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class BinDatabaseSetup {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static void main(String[] args) {
        insertMockData();
    }

    public static void insertMockData() {
        // The ? symbols are placeholders. This is called a PreparedStatement and it is much safer/faster.
        String sql = "INSERT INTO Bins (bin_id, status, max_weight_capacity, max_volume_m3, accessibility_score) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int totalBinsCreated = 0;

            // Loop 1: 3 Aisles
            for (int aisle = 1; aisle <= 3; aisle++) {
                // Loop 2: 2 Shelves per Aisle
                for (int shelf = 1; shelf <= 2; shelf++) {
                    // Loop 3: 3 Levels per Shelf
                    for (int level = 1; level <= 3; level++) {
                        // Loop 4: 3 Bins per Level
                        for (int bin = 1; bin <= 3; bin++) {
                            
                            // Generate the unique ID (e.g., "A1-S2-L3-B1")
                            String binId = "A" + aisle + "-S" + shelf + "-L" + level + "-B" + bin;
                            String status = "Empty"; 
                            
                            double maxWeight = 0;
                            double maxVolume = 0; 
                            int accessibilityScore = 0;

                            switch (level) {
                                case 1:
                                    maxWeight = 500.0;
                                    maxVolume = 1.5;        // Level 1: 1.5 cubic meters
                                    accessibilityScore = 5;
                                    break;
                                case 2:
                                    maxWeight = 250.0;
                                    maxVolume = 1.0;        // Level 2: 1.0 cubic meters
                                    accessibilityScore = 3;
                                    break;
                                case 3: 
                                    maxWeight = 100.0;
                                    maxVolume = 0.5;        // Level 3: 0.5 cubic meters
                                    accessibilityScore = 1;
                                    break;
                                default:
                                    break;
                            }

                            // Fill in the ? placeholders in our SQL string
                            pstmt.setString(1, binId);
                            pstmt.setString(2, status);
                            pstmt.setDouble(3, maxWeight);
                            pstmt.setDouble(4, maxVolume);       // <--- Inserted at position 4
                            pstmt.setInt(5, accessibilityScore);

                            
                            // Execute the insert for this specific bin
                            pstmt.executeUpdate();
                            totalBinsCreated++;
                        }
                    }
                }
            }
            
            System.out.println("Success: " + totalBinsCreated + " bins have been initialized in the Digital Twin.");

        } catch (SQLException e) {
            System.out.println("Failed to insert data: " + e.getMessage());
        }
    }
}

