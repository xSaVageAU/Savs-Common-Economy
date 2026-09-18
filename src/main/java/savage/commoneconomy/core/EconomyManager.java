package savage.commoneconomy.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.model.AccountData;
import savage.commoneconomy.core.storage.EconomyStorage;
import savage.commoneconomy.core.storage.JsonStorage;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The central logic for the Savs Common Economy mod.
 * Handles caching, balance manipulation, and sync orchestration.
 */
public class EconomyManager implements EconomyService {
    // Holder pattern: lazy initialization + thread-safe (JVM guarantees atomic class loading)
    private static class Holder {
        static final EconomyManager INSTANCE = new EconomyManager();
    }
    
    // In-memory cache for player accounts
    private final Cache<UUID, AccountData> accountCache;
    private EconomyStorage storage;
    
    // Dedicated thread pool for DB/Redis IO
    private final ExecutorService ioExecutor;

    private EconomyManager() {
        this.ioExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "SavsEconomy-IO");
            t.setDaemon(true);
            return t;
        });
        
        this.accountCache = Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();
                
        initStorage();
    }
    
    private void initStorage() {
        var config = ConfigManager.getConfig();
        String type = config.storage.type.toUpperCase();
        if ("MYSQL".equals(type) || "POSTGRESQL".equals(type) || "MARIADB".equals(type) || "SQLITE".equals(type)) {
            this.storage = new savage.commoneconomy.core.storage.SqlStorage(ioExecutor);
        } else {
            this.storage = new JsonStorage(ioExecutor);
        }

        if (config.redis.enabled) {
            savage.commoneconomy.core.sync.RedisManager.getInstance().connect();
        }
    }

    public void invalidateCache(UUID uuid) {
        accountCache.invalidate(uuid);
    }

    public static EconomyManager getInstance() {
        return Holder.INSTANCE;
    }

    public ExecutorService getIoExecutor() {
        return ioExecutor;
    }

    /**
     * Gets a player's balance asynchronously.
     */
    public CompletableFuture<BigDecimal> getBalance(UUID uuid) {
        return getOrCreateAccount(uuid, null).thenApply(AccountData::getBalance);
    }

    /**
     * Gets a player's balance synchronously from the cache.
     * Returns default balance if no cached entry exists.
     * Safe to call from the server thread without blocking.
     */
    @Override
    public BigDecimal getCachedBalance(UUID uuid) {
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            return data.getBalance();
        }
        return ConfigManager.getConfig().defaultBalance;
    }

    /**
     * Adds balance to a player's account asynchronously with optimistic locking.
     * @return CompletableFuture completing with true if successful.
     */
    @Override
    public CompletableFuture<Boolean> addBalance(UUID uuid, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) return CompletableFuture.completedFuture(false);
        return retryBalanceUpdate(uuid, amount, true, 5);
    }

    /**
     * Removes balance from a player's account asynchronously with optimistic locking.
     * @return CompletableFuture completing with true if successful.
     */
    @Override
    public CompletableFuture<Boolean> removeBalance(UUID uuid, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) return CompletableFuture.completedFuture(false);
        return retryBalanceUpdate(uuid, amount, false, 5);
    }

    /**
     * Core balance update with optimistic locking and retry.
     * @param isAdd true to add, false to subtract.
     * @param retriesLeft number of retries remaining.
     */
    private CompletableFuture<Boolean> retryBalanceUpdate(UUID uuid, BigDecimal amount, boolean isAdd, int retriesLeft) {
        // Always reload from storage to get the latest version
        accountCache.invalidate(uuid);

        return getOrCreateAccount(uuid, null).thenComposeAsync(account -> {
            BigDecimal currentBalance = account.getBalance();
            long expectedVersion = account.getVersion();

            BigDecimal newBalance;
            if (isAdd) {
                newBalance = currentBalance.add(amount);
            } else {
                if (currentBalance.compareTo(amount) < 0) {
                    return CompletableFuture.completedFuture(false); // Insufficient funds
                }
                newBalance = currentBalance.subtract(amount);
            }

            // Create updated account data with incremented version
            AccountData updated = new AccountData(account.getName(), newBalance, expectedVersion + 1);

            return storage.saveAccountIfVersionMatches(uuid, updated, expectedVersion).thenComposeAsync(success -> {
                if (success) {
                    // CAS succeeded — update cache
                    accountCache.put(uuid, updated);
                    if (ConfigManager.getConfig().redis.enabled) {
                        savage.commoneconomy.core.sync.RedisManager.getInstance().publishUpdate(uuid);
                    }
                    return CompletableFuture.completedFuture(true);
                } else if (retriesLeft > 1) {
                    // Version conflict — invalidate cache and retry
                    accountCache.invalidate(uuid);
                    try {
                        Thread.sleep(5 + (long)(Math.random() * 15));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return retryBalanceUpdate(uuid, amount, isAdd, retriesLeft - 1);
                } else {
                    SavsCommonEconomy.LOGGER.error("Balance update failed after all retries for UUID: " + uuid);
                    return CompletableFuture.completedFuture(false);
                }
            }, ioExecutor);
        }, ioExecutor);
    }

    /**
     * Gets or creates an account asynchronously.
     */
    public CompletableFuture<AccountData> getOrCreateAccount(UUID uuid, String name) {
        AccountData cached = accountCache.getIfPresent(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return storage.loadAccount(uuid).thenComposeAsync(stored -> {
            if (stored != null) {
                if (name != null && !name.equals(stored.getName())) {
                    stored.setName(name);
                    return storage.saveAccount(uuid, stored).thenApply(v -> {
                        accountCache.put(uuid, stored);
                        return stored;
                    });
                }
                accountCache.put(uuid, stored);
                return CompletableFuture.completedFuture(stored);
            }

            AccountData newAccount = new AccountData(name != null ? name : "Unknown", ConfigManager.getConfig().defaultBalance);
            return storage.saveAccount(uuid, newAccount).thenApply(v -> {
                accountCache.put(uuid, newAccount);
                return newAccount;
            });
        }, ioExecutor);
    }

    /**
     * Sets a player's balance asynchronously.
     */
    public CompletableFuture<Void> setBalance(UUID uuid, BigDecimal balance) {
        return getOrCreateAccount(uuid, null).thenComposeAsync(account -> {
            account.setBalance(balance);
            account.incrementVersion();
            return storage.saveAccount(uuid, account).thenAccept(v -> {
                if (ConfigManager.getConfig().redis.enabled) {
                    savage.commoneconomy.core.sync.RedisManager.getInstance().publishUpdate(uuid);
                }
            });
        }, ioExecutor);
    }

    /**
     * Resets a player's balance to the default starting balance asynchronously.
     */
    public CompletableFuture<Void> resetBalance(UUID uuid) {
        return setBalance(uuid, ConfigManager.getConfig().defaultBalance);
    }

    public CompletableFuture<Boolean> hasAccount(UUID uuid) {
        if (accountCache.getIfPresent(uuid) != null) return CompletableFuture.completedFuture(true);
        return storage.loadAccount(uuid).thenApply(Objects::nonNull);
    }

    public CompletableFuture<Void> createAccount(UUID uuid, String name) {
        AccountData account = new AccountData(name, ConfigManager.getConfig().defaultBalance);
        accountCache.put(uuid, account);
        return storage.saveAccount(uuid, account);
    }

    /**
     * Formats a balance with the configured currency symbol.
     */
    @Override
    public String format(BigDecimal balance) {
        DecimalFormat df = new DecimalFormat("#,##0.00");
        var config = ConfigManager.getConfig();
        String symbol = config.currencySymbol;
        return config.symbolBeforeAmount ? symbol + df.format(balance) : df.format(balance) + symbol;
    }

    /**
     * Returns the top accounts for baltop asynchronously.
     */
    public CompletableFuture<List<AccountData>> getTopAccounts(int limit) {
        return storage.loadAllAccounts().thenApply(accounts -> 
            accounts.values().stream()
                .sorted((a, b) -> b.getBalance().compareTo(a.getBalance()))
                .limit(limit)
                .collect(Collectors.toList())
        );
    }

    /**
     * Looks up a UUID by player name from the storage asynchronously.
     */
    public CompletableFuture<UUID> getUUIDFromName(String name) {
        return storage.loadAllAccounts().thenApply(accounts -> 
            accounts.entrySet().stream()
                .filter(entry -> entry.getValue().getName().equalsIgnoreCase(name))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null)
        );
    }

    /**
     * Gets all known player names asynchronously (for suggestions).
     */
    public CompletableFuture<List<String>> getAllPlayerNames() {
        return storage.loadAllAccounts().thenApply(accounts -> 
            accounts.values().stream()
                .map(AccountData::getName)
                .collect(Collectors.toList())
        );
    }

    /**
     * Gracefully shuts down the economy engine.
     */
    public void shutdown() {
        storage.shutdown();
        savage.commoneconomy.core.sync.RedisManager.getInstance().shutdown();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
        }
    }
}
