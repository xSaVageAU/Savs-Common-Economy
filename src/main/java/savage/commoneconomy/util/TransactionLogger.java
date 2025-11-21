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
    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void log(String type, String source, String target, BigDecimal amount, String details) {
        EXECUTOR.submit(() -> {
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
        });
    }

    public static java.util.List<LogEntry> searchLogs(String target, LocalDateTime cutoff) {
        if (!LOG_FILE.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.List<LogEntry> results = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(LOG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Line format: [yyyy-MM-dd HH:mm:ss] [TYPE] Source -> Target: $Amount (Details)
                if (line.length() < 21) continue;

                try {
                    // Parse timestamp
                    String timestampStr = line.substring(1, 20);
                    LocalDateTime timestamp = LocalDateTime.parse(timestampStr, DATE_FORMAT);
                    
                    if (timestamp.isAfter(cutoff)) {
                        if (target.equals("*") || line.toLowerCase().contains(target.toLowerCase())) {
                            // Parse the rest of the line
                            // Expected: [TYPE] Source -> Target: $Amount (Details)
                            String rest = line.substring(22);
                            int typeEnd = rest.indexOf(']');
                            String type = rest.substring(1, typeEnd);
                            
                            String content = rest.substring(typeEnd + 2); // Skip "] "
                            String[] parts = content.split(" -> ");
                            String source = parts[0];
                            
                            String remaining = parts[1];
                            int amountStart = remaining.indexOf(": $");
                            String targetName = remaining.substring(0, amountStart);
                            
                            String amountAndDetails = remaining.substring(amountStart + 3);
                            int detailsStart = amountAndDetails.indexOf(" (");
                            String amountStr = amountAndDetails.substring(0, detailsStart);
                            String details = amountAndDetails.substring(detailsStart + 2, amountAndDetails.length() - 1);
                            
                            results.add(new LogEntry(timestamp, type, source, targetName, new BigDecimal(amountStr), details));
                        }
                    }
                } catch (Exception e) {
                    // Ignore malformed lines, but maybe log debug if needed
                    // SavsCommonEconomy.LOGGER.warn("Malformed log line: " + line);
                }
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read economy log", e);
        }
        
        // Reverse to show newest first
        java.util.Collections.reverse(results);
        return results;
    }

    public static class LogEntry {
        public final LocalDateTime timestamp;
        public final String type;
        public final String source;
        public final String target;
        public final BigDecimal amount;
        public final String details;

        public LogEntry(LocalDateTime timestamp, String type, String source, String target, BigDecimal amount, String details) {
            this.timestamp = timestamp;
            this.type = type;
            this.source = source;
            this.target = target;
            this.amount = amount;
            this.details = details;
        }
    }
}
