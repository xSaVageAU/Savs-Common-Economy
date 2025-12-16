package savage.commoneconomy.util;

import com.google.gson.Gson;
import com.savage.redislib.RedisService;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.config.EconomyConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.server.network.ServerPlayerEntity;

import java.math.BigDecimal;
import java.util.UUID;

public class RealRedisBackend implements RedisBackend {
    private final Gson gson = new Gson();
    private final EconomyConfig.RedisConfig config;
    private MinecraftServer server;
    private final String channelName;

    public RealRedisBackend(EconomyConfig.RedisConfig config) {
        this.config = config;
        this.channelName = config.channel != null ? config.channel : "savs:economy";
    }

    @Override
    public void connect() {
        if (!RedisService.isReady()) {
            SavsCommonEconomy.LOGGER.warn("Savs-Redis-Lib is present but not ready/connected.");
            return;
        }

        // Subscribe
        RedisService.get().subscribe(channelName, (channel, message) -> {
            if (channel.equals(channelName)) {
                handleMessage(message);
            }
        });
        
        SavsCommonEconomy.LOGGER.info("Economy connected to Redis channel: " + channelName);
    }

    @Override
    public void shutdown() {
        // Lib handles shutdown
    }

    @Override
    public boolean isConnected() {
        return RedisService.isReady();
    }

    @Override
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void publishTransaction(UUID targetUuid, BigDecimal newBalance, String type, String sourcePlayer, String message) {
        if (!RedisService.isReady()) return;

        TransactionMessage msg = new TransactionMessage(
            targetUuid.toString(), 
            newBalance, 
            type, 
            sourcePlayer, 
            message
        );
        
        String json = gson.toJson(msg);
        RedisService.get().publish(channelName, json);

        if (config.debugLogging) {
            SavsCommonEconomy.LOGGER.info("Redis: Published transaction for " + targetUuid + " -> $" + newBalance);
        }
    }

    private void handleMessage(String json) {
        try {
            TransactionMessage message = gson.fromJson(json, TransactionMessage.class);
            UUID uuid = UUID.fromString(message.uuid);

            // Invalidate local cache
            EconomyManager.getInstance().invalidateCache(uuid);
            
            // Notify player if online
            if (server != null) {
                server.execute(() -> {
                     ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                     if (player != null && message.chatMessage != null) {
                         player.sendMessage(Text.literal(message.chatMessage), false);
                     }
                });
            }

            if (config.debugLogging) {
                SavsCommonEconomy.LOGGER.info("Redis: Received transaction for " + uuid + " -> $" + message.balance);
            }

        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.warn("Failed to handle Redis message: " + json, e);
        }
    }

    private static class TransactionMessage {
        String uuid;
        BigDecimal balance;
        String type;
        String sourcePlayer;
        String chatMessage;

        TransactionMessage(String uuid, BigDecimal balance, String type, String sourcePlayer, String chatMessage) {
            this.uuid = uuid;
            this.balance = balance;
            this.type = type;
            this.sourcePlayer = sourcePlayer;
            this.chatMessage = chatMessage;
        }
    }
}
