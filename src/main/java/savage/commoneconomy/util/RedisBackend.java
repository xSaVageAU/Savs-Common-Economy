package savage.commoneconomy.util;

import java.math.BigDecimal;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

public interface RedisBackend {
    void connect();
    void shutdown();
    boolean isConnected();
    void setServer(MinecraftServer server);
    void publishTransaction(UUID targetUuid, BigDecimal newBalance, String type, String sourcePlayer, String message);
}
