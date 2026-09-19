package savage.commoneconomy.core.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified configuration for Savs Common Economy.
 * Consolidates settings from EconomyConfig, DBCoreConfig, and RedisConfig.
 */
public class EconomyConfig {
    
    // --- General Settings ---
    public String language = "en_us";
    public BigDecimal defaultBalance = BigDecimal.valueOf(1000);
    public String currencySymbol = "$";
    public boolean symbolBeforeAmount = true;
    public boolean enableSellCommands = false;
    public boolean enableChestShops = true;
    public boolean enableBankNotes = true;
    public String shopVersion = "v1"; // "v1" or "v2"; which chest shop implementation runs (unrecognised values use "v1")
    public List<String> shopAllowedContainers = new ArrayList<>(List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel")); // shop v2 only: block ids, or block tags with a "#" prefix, that can hold a shop

    // --- Storage Settings ---
    public StorageConfig storage = new StorageConfig();

    public static class StorageConfig {
        public String type = "JSON"; // JSON, SQLITE, MYSQL, MARIADB, POSTGRESQL (anything else falls back to JSON)
        public String host = "localhost";
        public int port = 3306;
        public String database = "savs_economy";
        public String user = "root";
        public String password = "password";
        public String tablePrefix = "savs_eco_";
        public int poolSize = 10;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
    }

    // --- Redis Settings ---
    public RedisConfig redis = new RedisConfig();

    public NotificationMode apiNotificationMode = NotificationMode.ACTION_BAR;
    public NotificationMode commandNotificationMode = NotificationMode.CHAT;

    public static class RedisConfig {
        public boolean enabled = false;
        public String host = "localhost";
        public int port = 6379;
        public String password = "";
        public String channel = "savs-economy-updates";
        public boolean debugLogging = false;
        public int timeout_ms = 5000;
        public String client_name = "Savs-Economy-Node";
    }

    // --- Enums ---
    public enum NotificationMode {
        CHAT,
        ACTION_BAR,
        NONE
    }
}
