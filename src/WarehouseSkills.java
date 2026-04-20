import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class WarehouseSkills {
    private static final String DB_URL = "jdbc:sqlite:warehouse_demo.db";

    public static String getProductAnalysis(String productId) {
        String sql = "SELECT p.*, (SELECT SUM(quantity) FROM Sales WHERE product_id = p.product_id) as velocity " +
                     "FROM Products p WHERE p.product_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, productId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                // We added Volume to the string here so Zai can read it!
                return String.format("Product: %s, Weight: %.2fkg, Volume: %.2fm3, Velocity: %d", 
                       rs.getString("product_name"), 
                       rs.getDouble("weight_kg"), 
                       rs.getDouble("volume_m3"), 
                       rs.getInt("velocity"));
            }
        } catch (SQLException e) { return "DB Error: " + e.getMessage(); }
        return "Product not found.";
    }

    public static String findOptimalBin(double weight, double volume, int velocity) {
        int minAccess = (velocity > 100) ? 5 : 1; 
        String sql = "SELECT bin_id FROM Bins WHERE status = 'Empty' AND max_weight_capacity >= ? " +
                     "AND max_volume_m3 >= ? AND accessibility_score >= ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, weight); pstmt.setDouble(2, volume); pstmt.setInt(3, minAccess);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getString("bin_id") : "No bins available.";
        } catch (SQLException e) { return "Search error."; }
    }
}