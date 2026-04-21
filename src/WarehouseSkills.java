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

// Find the optimal bin for an incoming shipment based on weight, volume, and velocity
// - If velocity is high, we want a bin with high accessibility (score 5). If low, we can use less accessible bins (score 1).
// - The bin must also have enough weight and volume capacity for the shipment.
// - We will teach the AI to prioritize Half full bins first, saving the completely Empty bins for massive shipments
// - tell the database to save the good bins by picking the lowest acceptable score.
// - We make an EMERGENCY FALLBACK by allowing the AI to use bins that do NOT meet the accessibility requirement if there are no other options, but we make sure to put those at the very bottom of the priority list!
// - We also added a feature called SKU Affinity, which tries to group the same products together if possible. This is done by checking if either Product1 or Product2 in the bin matches the incoming productId, and if so, we give that bin the highest priority.

    public static String findOptimalBin(double weight, double volume, int velocity, String productId) {
        
        int targetAccess;
        if (velocity >= 80) targetAccess = 5;
        else if (velocity >= 30) targetAccess = 3;
        else targetAccess = 1;
        
        String sql = "SELECT bin_id FROM Bins " +
                     "WHERE status IN ('Empty', 'Half') " +
                     "AND (CASE WHEN status = 'Half' THEN max_weight_capacity * 0.5 ELSE max_weight_capacity END) >= ? " + 
                     "AND (CASE WHEN status = 'Half' THEN max_volume_m3 * 0.5 ELSE max_volume_m3 END) >= ? " + 
                     "ORDER BY " +
                     "  CASE WHEN Product1 = ? OR Product2 = ? THEN 0 ELSE 1 END, " + // PRIORITY 0: SKU Affinity (Group same products!)
                     "  CASE WHEN accessibility_score >= ? THEN 0 ELSE 1 END, " +     // PRIORITY 1: Emergency Fallback
                     "  CASE WHEN status = 'Half' THEN 1 ELSE 2 END, " +              // PRIORITY 2: Consolidation
                     "  accessibility_score ASC " +                                   // PRIORITY 3: Save premium bins
                     "LIMIT 1";
                     
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(DB_URL);
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
             
            pstmt.setDouble(1, weight); 
            pstmt.setDouble(2, volume); 
            
            // Bind the productId to check both slots in the bin
            pstmt.setString(3, productId); 
            pstmt.setString(4, productId); 
            
            // Target access moves to position 5
            pstmt.setInt(5, targetAccess); 
            
            java.sql.ResultSet rs = pstmt.executeQuery();
            
            return rs.next() ? rs.getString("bin_id") : "CRITICAL ALERT: Warehouse is physically full! No bins available.";
            
        } catch (java.sql.SQLException e) { 
            return "Database search error: " + e.getMessage(); 
        }
    }




    // NEW SKILL: Search by description
    public static String searchProductByDescription(String keyword) {
        String dbUrl = "jdbc:sqlite:warehouse_demo.db";
        // This searches BOTH the name column and the description column!
         String query = "SELECT product_id, product_name FROM products WHERE product_name LIKE ? OR product_description LIKE ? LIMIT 5";

        
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
             java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {
            
            // The % signs allow partial matches (e.g., searching "fan" finds "Ceiling Fan")
            pstmt.setString(1, "%" + keyword + "%");
            pstmt.setString(2, "%" + keyword + "%");
            java.sql.ResultSet rs = pstmt.executeQuery();
            
            StringBuilder results = new StringBuilder("Search Results:\n");
            boolean found = false;
            
            while(rs.next()) {
                found = true;
                results.append("- ID: ").append(rs.getString("product_id"))
                       .append(" | Name: ").append(rs.getString("product_name")).append("\n");
            }
            
            return found ? results.toString() : "No products found matching: " + keyword;
            
        } catch (Exception e) {
            return "Database search error: " + e.getMessage();
        }
    }
}