import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class InventoryTools {
    
    // added productId to the method
    public static String updateBinStatus(String binId, String aiSuggestedStatus, String productId) {
        String dbUrl = "jdbc:sqlite:warehouse_demo.db";
        
        // Query to check what is currently inside the bin
        String checkSql = "SELECT Product1, Product2 FROM Bins WHERE bin_id = ?";
        
        // Two possible update scenarios
        String fillSlot1Sql = "UPDATE Bins SET status = ?, Product1 = ? WHERE bin_id = ?";
        String fillSlot2Sql = "UPDATE Bins SET status = ?, Product2 = ? WHERE bin_id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
             
            checkStmt.setString(1, binId);
            ResultSet rs = checkStmt.executeQuery();

            if (rs.next()) {
                String p1 = rs.getString("Product1");
                String p2 = rs.getString("Product2"); 
                
                boolean p1Empty = (p1 == null || p1.trim().isEmpty());
                boolean p2Empty = (p2 == null || p2.trim().isEmpty());

                String finalSql;
                String calculatedStatus;

                // CLEAN LOGIC: Since Slot 1 is always filled first, we just check sequentially!
                if (p1Empty) {
                    finalSql = fillSlot1Sql;
                    calculatedStatus = "Half";
                } else if (p2Empty) {
                    finalSql = fillSlot2Sql;
                    calculatedStatus = "Full";
                } else {
                    return "SYSTEM ERROR: Bin " + binId + " is already physically full!";
                }

                try (PreparedStatement updateStmt = conn.prepareStatement(finalSql)) {
                    // We use calculatedStatus instead of the AI's suggested status
                    updateStmt.setString(1, calculatedStatus);
                    updateStmt.setString(2, productId);
                    updateStmt.setString(3, binId);
                    
                    int rows = updateStmt.executeUpdate();
                    return (rows > 0) ? "Success! Placed " + productId + " into " + binId + ". Status is automatically set to " + calculatedStatus : "Update failed.";
                }
            }
            return "Bin not found in database.";
        } catch (SQLException e) { 
            return "Database update error: " + e.getMessage(); 
        }
    }
}