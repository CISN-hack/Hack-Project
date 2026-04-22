import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class InventoryTools {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static String updateBinStatus(String binId, String aiSuggestedStatus, String productId) {
        String checkSql = "SELECT Product1, Product2 FROM Bins WHERE bin_id = ?";
        String fillSlot1Sql = "UPDATE Bins SET status = ?, Product1 = ? WHERE bin_id = ?";
        String fillSlot2Sql = "UPDATE Bins SET status = ?, Product2 = ? WHERE bin_id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
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
                    updateStmt.setString(1, calculatedStatus);
                    updateStmt.setString(2, productId);
                    updateStmt.setString(3, binId);

                    int rows = updateStmt.executeUpdate();
                    return (rows > 0)
                        ? "Success! Placed " + productId + " into " + binId + ". Status automatically set to " + calculatedStatus
                        : "Update failed.";
                }
            }
            return "Bin not found in database.";

        } catch (SQLException e) {
            return "Database update error: " + e.getMessage();
        }
    }

    // Moved from WarehouseSkills — owns all blocked_status writes
    public static int blockBinsByLocation(String location) {
        String blockSql = "UPDATE Bins SET blocked_status = 'Blocked' WHERE bin_id LIKE ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(blockSql)) {

            String searchPrefix = location.replaceAll("[^0-9a-zA-Z]", "").toUpperCase();
            if (searchPrefix.contains("AISLE")) {
                searchPrefix = searchPrefix.replace("AISLE", "A");
            }
            pstmt.setString(1, searchPrefix + "%");
            return pstmt.executeUpdate(); // returns number of bins blocked

        } catch (Exception e) {
            System.out.println("[DB ERROR] Could not block aisle: " + e.getMessage());
            return 0;
        }
    }

    public static int clearBinsByLocation(String location) {
        String clearSql = "UPDATE Bins SET blocked_status = 'Clear' WHERE bin_id LIKE ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(clearSql)) {

            String searchPrefix = location.replaceAll("[^0-9a-zA-Z]", "").toUpperCase();
            if (searchPrefix.contains("AISLE")) {
                searchPrefix = searchPrefix.replace("AISLE", "A");
            }
            pstmt.setString(1, searchPrefix + "%");
            return pstmt.executeUpdate(); // returns number of bins unblocked

        } catch (Exception e) {
            System.out.println("[DB ERROR] Could not clear aisle: " + e.getMessage());
            return 0;
        }
    }
}