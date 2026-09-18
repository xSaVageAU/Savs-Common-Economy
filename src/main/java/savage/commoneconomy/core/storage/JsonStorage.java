package savage.commoneconomy.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.model.AccountData;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * JSON-based storage for economy accounts.
 */
public class JsonStorage implements EconomyStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File STORAGE_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("savs-common-economy").resolve("balances.json").toFile();

    private final ExecutorService executor;
    private Map<UUID, AccountData> cachedData = new HashMap<>();

    public JsonStorage(ExecutorService executor) {
        this.executor = executor;
        load(); // Initial load into cache
    }

    private void load() {
        if (!STORAGE_FILE.exists()) return;

        try (FileReader reader = new FileReader(STORAGE_FILE)) {
            Type type = new TypeToken<Map<UUID, AccountData>>() {}.getType();
            Map<UUID, AccountData> data = GSON.fromJson(reader, type);
            if (data != null) {
                this.cachedData = data;
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to load balances from JSON!", e);
        }
    }

    private synchronized Map<UUID, AccountData> loadMap() {
        if (!STORAGE_FILE.exists()) {
            return new HashMap<>();
        }
        try (FileReader reader = new FileReader(STORAGE_FILE)) {
            Type type = new TypeToken<Map<UUID, AccountData>>() {}.getType();
            Map<UUID, AccountData> data = GSON.fromJson(reader, type);
            return data != null ? data : new HashMap<>();
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to load balances from JSON!", e);
            return new HashMap<>();
        }
    }

    private synchronized void saveMap(Map<UUID, AccountData> map) {
        try {
            File parent = STORAGE_FILE.getParentFile();
            if (!parent.exists()) parent.mkdirs();

            try (FileWriter writer = new FileWriter(STORAGE_FILE)) {
                GSON.toJson(map, writer);
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save balances to JSON!", e);
        }
    }

    private synchronized void save() {
        saveMap(cachedData);
    }

    @Override
    public CompletableFuture<AccountData> loadAccount(UUID uuid) {
        return CompletableFuture.completedFuture(cachedData.get(uuid));
    }

    @Override
    public CompletableFuture<Void> saveAccount(UUID uuid, AccountData data) {
        return CompletableFuture.runAsync(() -> {
            Map<UUID, AccountData> map = loadMap();
            map.put(uuid, data);
            saveMap(map);
            this.cachedData = map; // Update cache after async save
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> saveAccountIfVersionMatches(UUID uuid, AccountData data, long expectedVersion) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                AccountData existing = cachedData.get(uuid);
                long currentVersion = existing != null ? existing.getVersion() : 0;
                if (currentVersion != expectedVersion) {
                    return false; // Version conflict
                }
                Map<UUID, AccountData> map = loadMap();
                map.put(uuid, data);
                saveMap(map);
                this.cachedData = map;
                return true;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteAccount(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            Map<UUID, AccountData> map = loadMap();
            map.remove(uuid);
            saveMap(map);
            this.cachedData = map; // Update cache after async delete
        }, executor);
    }

    @Override
    public void shutdown() {
        save();
    }

    @Override
    public CompletableFuture<Map<UUID, AccountData>> loadAllAccounts() {
        return CompletableFuture.completedFuture(new HashMap<>(cachedData));
    }
}
