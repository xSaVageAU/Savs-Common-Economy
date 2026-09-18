package savage.commoneconomy.core.sync;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import savage.commoneconomy.core.EconomyManager;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;

import java.time.Duration;
import java.util.UUID;

/**
 * Manages Redis Pub/Sub for cross-server balance synchronization.
 */
public class RedisManager {

    private static class Holder {
        static final RedisManager INSTANCE = new RedisManager();
    }
    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private String channel;

    private RedisManager() {
        var config = ConfigManager.getConfig().redis;
        this.channel = config.channel;
    }

    public static RedisManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Connects to the Redis server and subscribes to the update channel.
     */
    public void connect() {
        if (client != null) return;

        var config = ConfigManager.getConfig().redis;
        try {
            RedisURI uri = RedisURI.Builder.redis(config.host, config.port)
                    .withPassword(config.password.toCharArray())
                    .withTimeout(Duration.ofMillis(30000))
                    .build();

            this.client = RedisClient.create(uri);
            this.pubSubConnection = client.connectPubSub();

            pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    if (channel.equals(RedisManager.this.channel)) {
                        handleMessage(message);
                    }
                }
            });

            pubSubConnection.sync().subscribe(channel);
            SavsCommonEconomy.LOGGER.info("Connected to Redis for balance synchronization.");
        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.error("Failed to connect to Redis!", e);
        }
    }

    /**
     * Publishes a balance update to other servers.
     */
    public void publishUpdate(UUID uuid) {
        if (pubSubConnection != null && pubSubConnection.isOpen()) {
            pubSubConnection.async().publish(channel, "UPDATE:" + uuid.toString());
        }
    }

    /**
     * Handles an incoming balance update message from another server.
     */
    private void handleMessage(String message) {
        if (message.startsWith("UPDATE:")) {
            String uuidStr = message.substring(7);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                EconomyManager.getInstance().invalidateCache(uuid);
                if (ConfigManager.getConfig().redis.debugLogging) {
                    SavsCommonEconomy.LOGGER.info("Redis: Invalidated cache for " + uuid);
                }
            } catch (IllegalArgumentException e) {
                SavsCommonEconomy.LOGGER.error("Received malformed Redis message: " + message);
            }
        }
    }

    /**
     * Shuts down the Redis connections.
     */
    public void shutdown() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
