package savage.commoneconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.config.EconomyConfig;
import savage.commoneconomy.config.WorthConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {
    private static EconomyManager instance;
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    private final File balanceFile;
    private final Gson gson;
    private EconomyConfig config;
    private WorthConfig worthConfig;

    private EconomyManager() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy");
        configDir.toFile().mkdirs();
        this.balanceFile = configDir.resolve("balances.json").toFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        loadConfig();
        loadWorthConfig();
        load();
    }

    public static EconomyManager getInstance() {
        if (instance == null) {
            instance = new EconomyManager();
        }
        return instance;
    }

    private void loadConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("config.json");
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
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

    private void loadWorthConfig() {
        if (!config.enableSellCommands) return;

        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("worth.json");
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            this.worthConfig = new WorthConfig();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this.worthConfig, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (FileReader reader = new FileReader(configFile)) {
                this.worthConfig = gson.fromJson(reader, WorthConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                this.worthConfig = new WorthConfig();
            }
        }
    }

    // ... existing load/save methods ...

    public boolean isSellEnabled() {
        return config.enableSellCommands;
    }

    public BigDecimal getItemPrice(String itemId) {
        if (worthConfig == null || worthConfig.itemPrices == null) return BigDecimal.ZERO;
        return worthConfig.itemPrices.getOrDefault(itemId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllItemPrices() {
        if (worthConfig == null) return new HashMap<>();
        return worthConfig.itemPrices;
    }

    // ... existing methods ...

    public void load() {
        if (balanceFile.exists()) {
            try (FileReader reader = new FileReader(balanceFile)) {
                Type type = new TypeToken<HashMap<UUID, AccountData>>() {}.getType();
                Map<UUID, AccountData> loaded = gson.fromJson(reader, type);
                if (loaded != null) {
                    accounts.putAll(loaded);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(balanceFile)) {
            gson.toJson(accounts, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public BigDecimal getBalance(UUID uuid) {
        return accounts.containsKey(uuid) ? accounts.get(uuid).balance : config.defaultBalance;
    }

    public void setBalance(UUID uuid, BigDecimal amount) {
        AccountData data = accounts.computeIfAbsent(uuid, k -> new AccountData("Unknown", config.defaultBalance));
        data.balance = amount;
        save();
    }

    public boolean addBalance(UUID uuid, BigDecimal amount) {
        BigDecimal current = getBalance(uuid);
        setBalance(uuid, current.add(amount));
        return true;
    }

    public boolean removeBalance(UUID uuid, BigDecimal amount) {
        BigDecimal current = getBalance(uuid);
        if (current.compareTo(amount) >= 0) {
            setBalance(uuid, current.subtract(amount));
            return true;
        }
        return false;
    }

    public boolean hasAccount(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    public void createAccount(UUID uuid, String name) {
        if (!accounts.containsKey(uuid)) {
            accounts.put(uuid, new AccountData(name, config.defaultBalance));
            save();
        } else {
            // Update name if changed
            AccountData data = accounts.get(uuid);
            if (!data.name.equals(name)) {
                data.name = name;
                save();
            }
        }
    }

    public void resetBalance(UUID uuid) {
        setBalance(uuid, config.defaultBalance);
    }

    public UUID getUUID(String name) {
        for (Map.Entry<UUID, AccountData> entry : accounts.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public java.util.Collection<String> getOfflinePlayerNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (AccountData data : accounts.values()) {
            names.add(data.name);
        }
        return names;
    }

    public String format(BigDecimal amount) {
        if (config.symbolBeforeAmount) {
            return config.currencySymbol + amount.toString();
        } else {
            return amount.toString() + config.currencySymbol;
        }
    }

    public java.util.List<AccountData> getTopAccounts(int limit) {
        java.util.List<AccountData> list = new java.util.ArrayList<>(accounts.values());
        list.sort((a, b) -> b.balance.compareTo(a.balance));
        if (list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    public static class AccountData {
        public String name;
        public BigDecimal balance;

        AccountData(String name, BigDecimal balance) {
            this.name = name;
            this.balance = balance;
        }
    }
}
