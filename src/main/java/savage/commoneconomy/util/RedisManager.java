package savage.commoneconomy.util;

import com.google.gson.Gson;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.config.EconomyConfig;

import java.math.BigDecimal;
import java.util.UUID;

public class RedisManager {
    private static RedisManager instance;
    private RedisClient redisClient;
    private StatefulRedisPubSubConnection<String, String> subConnection; // For subscribing
    private StatefulRedisConnection<String, String> pubConnection; // For publishing
    private final Gson gson = new Gson();
    private final EconomyConfig.RedisConfig config;
    private boolean connected = false;
    private net.minecraft.server.MinecraftServer server;

    private RedisManager(EconomyConfig.RedisConfig config) {
        this.config = config;
    }
    
    public void setServer(net.minecraft.server.MinecraftServer server) {
        this.server = server;
    }

    public static RedisManager getInstance() {
        if (instance == null) {
            EconomyConfig.RedisConfig config = EconomyManager.getInstance().getConfig().redis;
            instance = new RedisManager(config);
            if (config.enabled) {
                instance.connect();
            }
        }
        return instance;
    }

    private void connect() {
        try {
            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(config.host)
                    .withPort(config.port);

            if (config.password != null && !config.password.isEmpty()) {
                uriBuilder.withPassword(config.password.toCharArray());
            }

            RedisURI redisURI = uriBuilder.build();
            redisClient = RedisClient.create(redisURI);
            
            // Connection for subscribing (receiving messages)
            subConnection = redisClient.connectPubSub();
            subConnection.addListener(new io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    handleMessage(message);
                }
            });
            RedisPubSubCommands<String, String> subCommands = subConnection.sync();
            subCommands.subscribe(config.channel);
            
            // Separate connection for publishing (sending messages)
            pubConnection = redisClient.connect();
            
            connected = true;
            SavsCommonEconomy.LOGGER.info("Redis Pub/Sub connected successfully on channel: " + config.channel);

        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.warn("Failed to connect to Redis. Continuing without real-time sync.", e);
            connected = false;
        }
    }

    public void publishBalanceUpdate(UUID uuid, BigDecimal newBalance) {
        publishTransaction(uuid, newBalance, null, null, null);
    }
    
    public void publishTransaction(UUID targetUuid, BigDecimal newBalance, String type, String sourcePlayer, String message) {
        if (!connected || pubConnection == null) return;

        try {
            TransactionMessage msg = new TransactionMessage(
                targetUuid.toString(), 
                newBalance, 
                type, 
                sourcePlayer, 
                message
            );
            String json = gson.toJson(msg);
            RedisCommands<String, String> commands = pubConnection.sync();
            commands.publish(config.channel, json);
            if (config.debugLogging) {
                SavsCommonEconomy.LOGGER.info("Redis: Published transaction for " + targetUuid + " -> $" + newBalance);
            }
        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.warn("Failed to publish transaction to Redis", e);
        }
    }

    private void handleMessage(String json) {
        try {
            TransactionMessage message = gson.fromJson(json, TransactionMessage.class);
            UUID uuid = UUID.fromString(message.uuid);

            // Invalidate local cache so next read fetches fresh data
            EconomyManager.getInstance().invalidateCache(uuid);
            
            // Notify player if they're online
            net.minecraft.server.network.ServerPlayerEntity player = getOnlinePlayer(uuid);
            if (config.debugLogging) {
                SavsCommonEconomy.LOGGER.info("Redis: Looking for player " + uuid + ", found: " + (player != null));
            }
            if (player != null && message.chatMessage != null) {
                if (config.debugLogging) {
                    SavsCommonEconomy.LOGGER.info("Redis: Sending message to player: " + message.chatMessage);
                }
                player.sendMessage(net.minecraft.text.Text.literal(message.chatMessage), false);
            } else if (config.debugLogging) {
                if (player == null) {
                    SavsCommonEconomy.LOGGER.info("Redis: Player not online on this server");
                }
                if (message.chatMessage == null) {
                    SavsCommonEconomy.LOGGER.info("Redis: No chat message in transaction");
                }
            }
            
            if (config.debugLogging) {
                SavsCommonEconomy.LOGGER.info("Redis: Received transaction for " + uuid + " -> $" + message.balance + " (cache invalidated)");
            }

        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.warn("Failed to handle Redis message: " + json, e);
        }
    }
    
    private net.minecraft.server.network.ServerPlayerEntity getOnlinePlayer(UUID uuid) {
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(uuid);
    }

    public void shutdown() {
        if (subConnection != null) {
            subConnection.close();
        }
        if (pubConnection != null) {
            pubConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    private static class TransactionMessage {
        String uuid;
        BigDecimal balance;
        String type; // "pay", "give", "take", etc.
        String sourcePlayer; // Who initiated the transaction
        String chatMessage; // The message to show the player

        TransactionMessage(String uuid, BigDecimal balance, String type, String sourcePlayer, String chatMessage) {
            this.uuid = uuid;
            this.balance = balance;
            this.type = type;
            this.sourcePlayer = sourcePlayer;
            this.chatMessage = chatMessage;
        }
    }
}
