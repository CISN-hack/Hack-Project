import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class LocalFileTools {
    public static String readLocalCustomerOrder(String filePath) {
        StringBuilder data = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) data.append(line).append("\n");
            return data.toString();
        } catch (IOException e) { return "File error: " + e.getMessage(); }
    }
}
