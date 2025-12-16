package savage.commoneconomy.util;

import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.config.EconomyConfig;
import net.fabricmc.loader.api.FabricLoader;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Economy-specific Redis manager.
 * Acts as a proxy/gatekeeper. If savs-redis-lib is loaded, uses it.
 * If not, operations are no-ops.
 */
public class RedisManager {
    private static RedisManager instance;
    private RedisBackend backend;
    private final EconomyConfig.RedisConfig config;

    private RedisManager(EconomyConfig.RedisConfig config) {
        this.config = config;
        
        if (config.enabled) {
            if (FabricLoader.getInstance().isModLoaded("savs-redis-lib")) {
                try {
                    // Instantiate via direct construction since the class exists at compile time,
                    // but guard it with the mod check so it's not loaded if mod is missing.
                    this.backend = new RealRedisBackend(config);
                    this.connect();
                } catch (Throwable t) {
                     SavsCommonEconomy.LOGGER.error("Failed to initialize Redis Backend despite lib being present", t);
                }
            } else {
                SavsCommonEconomy.LOGGER.warn("=================================================");
                SavsCommonEconomy.LOGGER.warn(" [SavsCommonEconomy] REDIS ENABLED BUT LIB MISSING");
                SavsCommonEconomy.LOGGER.warn(" Please install 'savs-redis-lib' to use Redis features.");
                SavsCommonEconomy.LOGGER.warn(" Recurring to Local Database mode.");
                SavsCommonEconomy.LOGGER.warn("=================================================");
            }
        }
    }
    
    public void setServer(net.minecraft.server.MinecraftServer server) {
        if (backend != null) backend.setServer(server);
    }

    public static RedisManager getInstance() {
        if (instance == null) {
            EconomyConfig.RedisConfig config = EconomyManager.getInstance().getConfig().redis;
            instance = new RedisManager(config);
        }
        return instance;
    }

    private void connect() {
        if (backend != null) backend.connect();
    }

    public void publishBalanceUpdate(UUID uuid, BigDecimal newBalance) {
        publishTransaction(uuid, newBalance, null, null, null);
    }
    
    public void publishTransaction(UUID targetUuid, BigDecimal newBalance, String type, String sourcePlayer, String message) {
        if (backend != null) {
            backend.publishTransaction(targetUuid, newBalance, type, sourcePlayer, message);
        }
    }

    public void shutdown() {
        if (backend != null) backend.shutdown();
    }

    public boolean isConnected() {
        return backend != null && backend.isConnected();
    }
}
