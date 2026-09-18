package savage.commoneconomy.core.log;

import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple thread-safe logger for economy transactions.
 */
public class TransactionLogger {
    private static final File LOG_FILE = FabricLoader.getInstance().getGameDir()
            .resolve("logs/economy.log").toFile();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * Logs a transaction asynchronously to avoid blocking the main server thread.
     */
    public static void log(String type, String sender, String receiver, BigDecimal amount, String reason) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        // Format: [yyyy-MM-dd HH:mm:ss] [TYPE] Source -> Target: $Amount (Reason)
        String logEntry = String.format("[%s] [%s] %s -> %s: $%s (%s)\n",
                timestamp, type, sender, receiver, amount.toPlainString(), reason);

        EXECUTOR.execute(() -> {
            try {
                File parent = LOG_FILE.getParentFile();
                if (!parent.exists()) parent.mkdirs();

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                    writer.write(logEntry);
                }
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to write to transaction log!", e);
            }
        });
    }

    /**
     * Searches logs for entries involving a target player after a cutoff time.
     */
    public static List<LogEntry> searchLogs(String target, LocalDateTime cutoff) {
        if (!LOG_FILE.exists()) return Collections.emptyList();

        List<LogEntry> results = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(LOG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 21) continue;
                try {
                    String timestampStr = line.substring(1, 20);
                    LocalDateTime timestamp = LocalDateTime.parse(timestampStr, FORMATTER);

                    if (timestamp.isAfter(cutoff)) {
                        if (target.equals("*") || line.toLowerCase().contains(target.toLowerCase())) {
                            // Format: [timestamp] [TYPE] Source -> Target: $Amount (Reason)
                            String rest = line.substring(22);
                            int typeEnd = rest.indexOf(']');
                            String type = rest.substring(1, typeEnd);

                            String content = rest.substring(typeEnd + 2);
                            String[] parts = content.split(" -> ");
                            String source = parts[0];

                            String remaining = parts[1];
                            int amountStart = remaining.indexOf(": $");
                            String targetName = remaining.substring(0, amountStart);

                            String amountAndReason = remaining.substring(amountStart + 3);
                            int reasonStart = amountAndReason.indexOf(" (");
                            String amountStr = amountAndReason.substring(0, reasonStart);
                            String reason = amountAndReason.substring(reasonStart + 2, amountAndReason.length() - 1);

                            results.add(new LogEntry(timestamp, type, source, targetName, new BigDecimal(amountStr), reason));
                        }
                    }
                } catch (Exception e) {
                    // Ignore malformed lines
                }
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read transaction logs!", e);
        }

        Collections.reverse(results); // Newest first
        return results;
    }

    public static class LogEntry {
        public final LocalDateTime timestamp;
        public final String type;
        public final String source;
        public final String target;
        public final BigDecimal amount;
        public final String reason;

        public LogEntry(LocalDateTime timestamp, String type, String source, String target, BigDecimal amount, String reason) {
            this.timestamp = timestamp;
            this.type = type;
            this.source = source;
            this.target = target;
            this.amount = amount;
            this.reason = reason;
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
