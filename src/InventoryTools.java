import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class InventoryTools {
    public static String updateBinStatus(String binId, String status) {
        String sql = "UPDATE Bins SET status = ? WHERE bin_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:warehouse_demo.db");
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status); pstmt.setString(2, binId);
            return (pstmt.executeUpdate() > 0) ? "Updated " + binId : "Bin not found.";
        } catch (SQLException e) { return "Update failed."; }
    }
}