package savage.commoneconomy.config;

import java.math.BigDecimal;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(1000);
    public String currencySymbol = "$";
    public boolean symbolBeforeAmount = true;
    public boolean enableSellCommands = false;
    public boolean enableChestShops = true;
    
    public StorageConfig storage = new StorageConfig();

    public static class StorageConfig {
        public String type = "JSON";
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
    
    public RedisConfig redis = new RedisConfig();
    
    public static class RedisConfig {
        public boolean enabled = false;

        public String channel = "savs-economy-updates";
        public boolean debugLogging = false; 
    }

    public NotificationMode apiNotificationMode = NotificationMode.ACTION_BAR;
    public NotificationMode commandNotificationMode = NotificationMode.CHAT;

    public enum NotificationMode {
        CHAT,
        ACTION_BAR,
        NONE
    }

    public enum StorageType {
        JSON,
        SQLITE,
        MYSQL,
        POSTGRESQL
    }
}
