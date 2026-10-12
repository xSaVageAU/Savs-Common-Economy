package savage.commoneconomy.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import savage.commoneconomy.config.EconomyConfig.NotificationMode;

/**
 * Sends a balance notification to a player according to a configured {@link NotificationMode}.
 */
public class NotificationHelper {

    public static void send(ServerPlayer player, Component message, NotificationMode mode) {
        switch (mode) {
            case CHAT -> player.sendSystemMessage(message, false);
            case ACTION_BAR -> player.sendSystemMessage(message, true);
            case NONE -> { }
        }
    }
}
