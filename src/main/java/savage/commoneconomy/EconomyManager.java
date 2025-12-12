package savage.commoneconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.config.EconomyConfig;
import savage.commoneconomy.config.WorthConfig;
import savage.commoneconomy.storage.EconomyStorage;
import savage.commoneconomy.storage.JsonStorage;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {
    private static EconomyManager instance;
    private EconomyStorage storage;
    private final Gson gson;
    private EconomyConfig config;

    public EconomyConfig getConfig() {
        return config;
    }

    public static EconomyStorage getStorage() {
        return getInstance().storage;
    }

    public static EconomyManager getInstance() {
        if (instance == null) {
            instance = new EconomyManager();
        }
        return instance;
    }

    // Caching
    private final com.github.benmanes.caffeine.cache.Cache<UUID, AccountData> accountCache;
    private final com.github.benmanes.caffeine.cache.Cache<String, UUID> uuidCache;
    private final com.github.benmanes.caffeine.cache.Cache<String, java.util.List<String>> offlineNamesCache;

    private EconomyManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
        
        // Initialize Caches
        this.accountCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                .build();
                
        this.uuidCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, java.util.concurrent.TimeUnit.HOURS)
                .build();
                
        this.offlineNamesCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(1) // Singleton cache
                .expireAfterWrite(1, java.util.concurrent.TimeUnit.MINUTES)
                .build();
    }
    
    public void initStorage() {
        if (storage != null) return; // Already initialized

        String type = config.storage.type.toUpperCase();
        int maxRetries = 10;
        int attempt = 0;
        
        while (attempt < maxRetries) {
            try {
                switch (type) {
                    case "MYSQL":
                        storage = new savage.commoneconomy.storage.MysqlStorage(this, config.storage.host, config.storage.port, config.storage.database, config.storage.user, config.storage.password, config.storage.tablePrefix);
                        break;
                    case "SQLITE":
                        storage = new savage.commoneconomy.storage.SqliteStorage(this, config.storage.tablePrefix);
                        break;
                    case "POSTGRES":
                    case "POSTGRESQL":
                        storage = new savage.commoneconomy.storage.PostgresStorage(this, config.storage.host, config.storage.port, config.storage.database, config.storage.user, config.storage.password, config.storage.tablePrefix);
                        break;
                    default:
                        storage = new JsonStorage(this);
                        break;
                }
                // If successful, break loop
                savage.commoneconomy.SavsCommonEconomy.LOGGER.info("Economy Storage initialized successfully: " + type);
                break;
            } catch (Exception e) {
                attempt++;
                savage.commoneconomy.SavsCommonEconomy.LOGGER.warn("Failed to initialize economy storage (Attempt " + attempt + "/" + maxRetries + "). Retrying in 2 seconds...", e);
                
                if (attempt >= maxRetries) {
                    savage.commoneconomy.SavsCommonEconomy.LOGGER.error("Could not initialize economy storage after " + maxRetries + " attempts. Falling back to JSON/Disable.");
                    // Fallback to JSON or throw?
                    // Let's fallback to JSON to prevent crash but data might be split.
                    // Actually, if DB fails, JSON fallback is risky for sync. Better to crash or disable.
                    throw new RuntimeException("Failed to connect to economy database", e);
                }
                
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void loadConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("config.json");
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            // Ensure directory exists
            configFile.getParentFile().mkdirs();
            
            this.config = new EconomyConfig();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this.config, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (FileReader reader = new FileReader(configFile)) {
                this.config = gson.fromJson(reader, EconomyConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                this.config = new EconomyConfig();
            }
        }
    }

    public void load() {
        storage.load();
    }

    public void save() {
        storage.save();
    }

    public BigDecimal getBalance(UUID uuid) {
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            return data.balance;
        }
        
        // Cache miss, load from storage
        BigDecimal balance = storage.getBalance(uuid);
        // We need version to cache properly, but getBalance only returns BigDecimal.
        // Ideally we should use getAccountData to populate cache.
        // For now, let's fetch full data if possible, or just cache the balance with a dummy version/name if we can't get full data easily without changing storage interface again.
        // Actually, we added getAccount to storage interface earlier!
        data = storage.getAccount(uuid);
        if (data != null) {
            accountCache.put(uuid, data);
            return data.balance;
        } else {
            // Account doesn't exist, return default but don't cache null unless we use Optional
            return config.defaultBalance;
        }
    }

    private net.minecraft.server.MinecraftServer server;

    public void setServer(net.minecraft.server.MinecraftServer server) {
        this.server = server;
    }

    public net.minecraft.server.MinecraftServer getServer() {
        return server;
    }

    public void setBalance(UUID uuid, BigDecimal amount) {
        setBalance(uuid, amount, true);
    }

    public void setBalance(UUID uuid, BigDecimal amount, boolean publishToRedis) {
        storage.setBalance(uuid, amount);
        accountCache.invalidate(uuid); 
        if (publishToRedis && config.redis.enabled) {
            savage.commoneconomy.util.RedisManager.getInstance().publishBalanceUpdate(uuid, amount);
        }
    }

    public boolean addBalance(UUID uuid, BigDecimal amount) {
        return addBalance(uuid, amount, true);
    }

    public boolean addBalance(UUID uuid, BigDecimal amount, boolean publishToRedis) {
        int retries = 10;
        while (retries > 0) {
            // Reload account data to get latest version
            AccountData data = getAccountData(uuid); 
            BigDecimal current = data != null ? data.balance : config.defaultBalance;
            long version = data != null ? data.version : 0;
            
            if (storage.setBalance(uuid, current.add(amount), version)) {
                // Update cache on success
                if (data != null) {
                    data.balance = current.add(amount);
                    data.version++;
                    accountCache.put(uuid, data);
                } else {
                    accountCache.invalidate(uuid);
                }
                
                if (publishToRedis && config.redis.enabled) {
                    savage.commoneconomy.util.RedisManager.getInstance().publishBalanceUpdate(uuid, current.add(amount));
                }
                
                return true;
            }
            // On failure, invalidate cache to ensure we get fresh data from DB next retry
            accountCache.invalidate(uuid);
            
            retries--;
            try {
                Thread.sleep(10 + (long)(Math.random() * 10)); // Small random backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false; // Failed after retries
    }

    public boolean removeBalance(UUID uuid, BigDecimal amount) {
        return removeBalance(uuid, amount, true);
    }

    public boolean removeBalance(UUID uuid, BigDecimal amount, boolean publishToRedis) {
        int retries = 10;
        while (retries > 0) {
            // Reload account data to get latest version
            AccountData data = getAccountData(uuid);
            BigDecimal current = data != null ? data.balance : config.defaultBalance;
            long version = data != null ? data.version : 0;
            
            if (current.compareTo(amount) >= 0) {
                if (storage.setBalance(uuid, current.subtract(amount), version)) {
                     // Update cache on success
                    if (data != null) {
                        data.balance = current.subtract(amount);
                        data.version++;
                        accountCache.put(uuid, data);
                    } else {
                        accountCache.invalidate(uuid);
                    }
                    
                    if (publishToRedis && config.redis.enabled) {
                        savage.commoneconomy.util.RedisManager.getInstance().publishBalanceUpdate(uuid, current.subtract(amount));
                    }
                    
                    return true;
                }
            } else {
                return false; // Insufficient funds
            }
            // On failure, invalidate cache
            accountCache.invalidate(uuid);
            
            retries--;
            try {
                Thread.sleep(10 + (long)(Math.random() * 10)); // Small random backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false; // Failed after retries
    }
    
    private AccountData getAccountData(UUID uuid) {
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            return data;
        }
        
        data = storage.getAccount(uuid);
        if (data != null) {
            accountCache.put(uuid, data);
        }
        return data;
    }

    public boolean hasAccount(UUID uuid) {
        if (accountCache.getIfPresent(uuid) != null) return true;
        return storage.hasAccount(uuid);
    }

    public void createAccount(UUID uuid, String name) {
        storage.createAccount(uuid, name);
        // Cache the new account
        accountCache.put(uuid, new AccountData(name, config.defaultBalance, 0));
        uuidCache.put(name.toLowerCase(), uuid);
        offlineNamesCache.invalidateAll(); // Invalidate names list
    }

    public void deleteAccount(UUID uuid) {
        storage.deleteAccount(uuid);
        // Invalidate all caches
        accountCache.invalidate(uuid);
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            uuidCache.invalidate(data.name.toLowerCase());
        }
        offlineNamesCache.invalidateAll();
    }
    
    public void invalidateCache(UUID uuid) {
        accountCache.invalidate(uuid);
    }

    public void resetBalance(UUID uuid) {
        setBalance(uuid, config.defaultBalance);
    }

    public UUID getUUID(String name) {
        UUID uuid = uuidCache.getIfPresent(name.toLowerCase());
        if (uuid != null) return uuid;
        
        uuid = storage.getUUID(name);
        if (uuid != null) {
            uuidCache.put(name.toLowerCase(), uuid);
        }
        return uuid;
    }

    public java.util.Collection<String> getOfflinePlayerNames() {
        java.util.List<String> names = offlineNamesCache.getIfPresent("all");
        if (names != null) return names;
        
        names = new java.util.ArrayList<>(storage.getOfflinePlayerNames());
        offlineNamesCache.put("all", names);
        return names;
    }

    public String format(BigDecimal amount) {
        if (config.symbolBeforeAmount) {
            return config.currencySymbol + amount.toString();
        } else {
            return amount.toString() + config.currencySymbol;
        }
    }

    // Leaderboard support
    public java.util.List<AccountData> getTopAccounts(int limit) {
        return storage.getTopAccounts(limit);
    }

    // Sell system support
    private WorthConfig worthConfig;

    public boolean isSellEnabled() {
        return config != null && config.enableSellCommands;
    }

    public BigDecimal getItemPrice(String itemId) {
        if (worthConfig == null) {
            loadWorthConfig();
        }
        return worthConfig.itemPrices.getOrDefault(itemId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllItemPrices() {
        if (worthConfig == null) {
            loadWorthConfig();
        }
        return new HashMap<>(worthConfig.itemPrices);
    }

    private void loadWorthConfig() {
        Path worthPath = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("worth.json");
        File worthFile = worthPath.toFile();

        if (!worthFile.exists()) {
            this.worthConfig = new WorthConfig();
            try (FileWriter writer = new FileWriter(worthFile)) {
                gson.toJson(this.worthConfig, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (FileReader reader = new FileReader(worthFile)) {
                this.worthConfig = gson.fromJson(reader, WorthConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                this.worthConfig = new WorthConfig();
            }
        }
    }

    public static class AccountData {
        public String name;
        public BigDecimal balance;
        public long version;

        public AccountData(String name, BigDecimal balance) {
            this(name, balance, 0);
        }

        public AccountData(String name, BigDecimal balance, long version) {
            this.name = name;
            this.balance = balance;
            this.version = version;
        }
    }
}
