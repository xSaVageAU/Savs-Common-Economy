package savage.commoneconomy.util;

import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TransactionLogger {

    private static final File LOG_FILE = FabricLoader.getInstance().getGameDir().resolve("logs/economy.log").toFile();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void log(String type, String source, String target, BigDecimal amount, String details) {
        // Run in a separate thread to avoid blocking the main server thread
        new Thread(() -> {
            try {
                if (!LOG_FILE.exists()) {
                    LOG_FILE.getParentFile().mkdirs();
                    LOG_FILE.createNewFile();
                }

                String timestamp = LocalDateTime.now().format(DATE_FORMAT);
                String logEntry = String.format("[%s] [%s] %s -> %s: $%s (%s)%n", 
                    timestamp, type, source, target, amount.toPlainString(), details);

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                    writer.write(logEntry);
                }

            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to write to economy log", e);
            }
        }).start();
    }
    public static java.util.List<String> searchLogs(String target, LocalDateTime cutoff) {
        if (!LOG_FILE.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.List<String> results = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(LOG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Line format: [yyyy-MM-dd HH:mm:ss] [TYPE] Source -> Target: $Amount (Details)
                if (line.length() < 21) continue;

                String timestampStr = line.substring(1, 20);
                try {
                    LocalDateTime timestamp = LocalDateTime.parse(timestampStr, DATE_FORMAT);
                    
                    if (timestamp.isAfter(cutoff)) {
                        if (target.equals("*") || line.toLowerCase().contains(target.toLowerCase())) {
                            results.add(line);
                        }
                    }
                } catch (Exception e) {
                    // Ignore malformed lines
                }
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read economy log", e);
        }
        
        // Reverse to show newest first
        java.util.Collections.reverse(results);
        return results;
    }
}
