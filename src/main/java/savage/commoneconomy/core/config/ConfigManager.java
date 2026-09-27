package savage.commoneconomy.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Handles JSON serialization for EconomyConfig.
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    
    private static final java.nio.file.Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy");
    private static final File CONFIG_FILE = CONFIG_DIR.resolve("config.json").toFile();
    private static final File WORTH_FILE = CONFIG_DIR.resolve("worth.json").toFile();
    private static final File PERMISSIONS_FILE = CONFIG_DIR.resolve("permissions.json").toFile();
    
    private static EconomyConfig currentConfig = new EconomyConfig();
    private static WorthConfig worthConfig = new WorthConfig();
    private static PermissionsConfig permissionsConfig = new PermissionsConfig();

    /**
     * Loads the config from disk, or saves default if it doesn't exist.
     */
    public static void load() {
        loadMain();
        loadWorth();
        loadPermissions();
    }

    private static void loadMain() {
        if (!CONFIG_FILE.exists()) {
            saveMain(); // Save defaults
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            currentConfig = GSON.fromJson(reader, EconomyConfig.class);
            if (currentConfig == null) {
                currentConfig = new EconomyConfig();
            }
            // Force save to embed any new config fields added in mod updates
            saveMain();
            SavsCommonEconomy.LOGGER.info("Successfully loaded configuration.");
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to load configuration!", e);
        }

        // Auto-migrate old Savs-Redis-Lib config (savs-redis.json) if present
        migrateOldRedisConfig();
    }

    /**
     * Migrates settings from the old savs-redis.json (Savs-Redis-Lib) into config.json.
     * Only runs once — renames the old file after migration to prevent re-migration.
     */
    private static void migrateOldRedisConfig() {
        File oldRedisConfig = FabricLoader.getInstance().getConfigDir().resolve("savs-redis.json").toFile();
        if (!oldRedisConfig.exists()) return;

        SavsCommonEconomy.LOGGER.info("Found old savs-redis.json — migrating Redis settings into config.json...");
        try (FileReader reader = new FileReader(oldRedisConfig)) {
            com.google.gson.JsonObject oldConfig = GSON.fromJson(reader, com.google.gson.JsonObject.class);
            if (oldConfig != null) {
                EconomyConfig.RedisConfig redis = currentConfig.redis;
                if (oldConfig.has("host")) redis.host = oldConfig.get("host").getAsString();
                if (oldConfig.has("port")) redis.port = oldConfig.get("port").getAsInt();
                if (oldConfig.has("password")) redis.password = oldConfig.get("password").getAsString();
                if (oldConfig.has("timeout_ms")) redis.timeout_ms = oldConfig.get("timeout_ms").getAsInt();
                if (oldConfig.has("client_name")) redis.client_name = oldConfig.get("client_name").getAsString();
                redis.enabled = true;

                saveMain();
                SavsCommonEconomy.LOGGER.info("Successfully migrated Redis settings into config.json.");

                // Rename old file to prevent re-migration
                File backup = new File(oldRedisConfig.getParent(), "savs-redis.json.migrated");
                if (oldRedisConfig.renameTo(backup)) {
                    SavsCommonEconomy.LOGGER.info("Renamed old savs-redis.json to savs-redis.json.migrated");
                }
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.warn("Failed to migrate old Redis config — you may need to configure Redis manually in config.json", e);
        }
    }

    /**
     * Saves all configurations to disk.
     */
    public static void save() {
        saveMain();
        saveWorth();
        savePermissions();
    }

    private static void saveMain() {
        try {
            File dir = CONFIG_DIR.toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(currentConfig, writer);
                SavsCommonEconomy.LOGGER.info("Successfully saved configuration.");
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save configuration!", e);
        }
    }

    private static void loadWorth() {
        if (!WORTH_FILE.exists()) {
            worthConfig = new WorthConfig();
            worthConfig.sellPrices.put("minecraft:apple", new java.math.BigDecimal("10.00"));
            worthConfig.buyPrices.put("minecraft:apple", new java.math.BigDecimal("20.00"));
            saveWorth();
            return;
        }

        try (FileReader reader = new FileReader(WORTH_FILE)) {
            // First, check for legacy format migration (old "itemPrices" -> new "sellPrices")
            com.google.gson.JsonObject rawJson = GSON.fromJson(reader, com.google.gson.JsonObject.class);
            
            if (rawJson != null && rawJson.has("itemPrices") && !rawJson.has("sellPrices")) {
                // Migrate old format: copy itemPrices to sellPrices
                SavsCommonEconomy.LOGGER.info("Migrating legacy worth.json: renaming 'itemPrices' to 'sellPrices'...");
                
                com.google.gson.JsonElement oldPrices = rawJson.get("itemPrices");
                rawJson.add("sellPrices", oldPrices);
                
                // Generate buyPrices dynamically (double the sell price)
                com.google.gson.JsonObject buyPrices = new com.google.gson.JsonObject();
                if (oldPrices.isJsonObject()) {
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : oldPrices.getAsJsonObject().entrySet()) {
                        double sellPrice = entry.getValue().getAsDouble();
                        buyPrices.addProperty(entry.getKey(), sellPrice * 2.0);
                    }
                }
                rawJson.add("buyPrices", buyPrices);
                rawJson.remove("itemPrices");
                
                // Parse the migrated JSON
                worthConfig = GSON.fromJson(rawJson, WorthConfig.class);
                if (worthConfig == null) {
                    worthConfig = new WorthConfig();
                }
                // Save the migrated file
                saveWorth();
                SavsCommonEconomy.LOGGER.info("Successfully migrated worth.json to new format.");
            } else {
                worthConfig = GSON.fromJson(rawJson, WorthConfig.class);
                if (worthConfig == null) {
                    worthConfig = new WorthConfig();
                    saveWorth();
                }
            }
            
            SavsCommonEconomy.LOGGER.info("Successfully loaded worth.json.");
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to load worth.json!", e);
        }
    }

    private static void saveWorth() {
        try {
            File dir = CONFIG_DIR.toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter writer = new FileWriter(WORTH_FILE)) {
                GSON.toJson(worthConfig, writer);
                SavsCommonEconomy.LOGGER.info("Successfully saved worth.json.");
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save worth.json!", e);
        }
    }

    /**
     * @return The active configuration instance.
     */
    public static EconomyConfig getConfig() {
        return currentConfig;
    }

    /**
     * @return The active worth configuration instance.
     */
    public static WorthConfig getWorth() {
        return worthConfig;
    }

    /**
     * @return The active permissions configuration instance.
     */
    public static PermissionsConfig getPermissions() {
        return permissionsConfig;
    }

    private static void loadPermissions() {
        if (!PERMISSIONS_FILE.exists()) {
            savePermissions();
            return;
        }

        try (FileReader reader = new FileReader(PERMISSIONS_FILE)) {
            PermissionsConfig loaded = GSON.fromJson(reader, PermissionsConfig.class);
            if (loaded == null || loaded.permissions == null) {
                permissionsConfig = new PermissionsConfig();
                savePermissions();
            } else {
                // Merge: add any new permission nodes that aren't in the file yet
                PermissionsConfig defaults = new PermissionsConfig();
                boolean changed = false;
                for (var entry : defaults.permissions.entrySet()) {
                    if (!loaded.permissions.containsKey(entry.getKey())) {
                        loaded.permissions.put(entry.getKey(), entry.getValue());
                        changed = true;
                        SavsCommonEconomy.LOGGER.info("Added new permission node: {} (default: {})", entry.getKey(), entry.getValue());
                    }
                }
                permissionsConfig = loaded;
                if (changed) savePermissions();
            }
            SavsCommonEconomy.LOGGER.info("Successfully loaded permissions.json.");
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to load permissions.json!", e);
        }
    }

    private static void savePermissions() {
        try {
            File dir = CONFIG_DIR.toFile();
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter writer = new FileWriter(PERMISSIONS_FILE)) {
                GSON.toJson(permissionsConfig, writer);
                SavsCommonEconomy.LOGGER.info("Successfully saved permissions.json.");
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save permissions.json!", e);
        }
    }
}
