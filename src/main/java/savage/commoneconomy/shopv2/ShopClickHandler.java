package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import savage.commoneconomy.core.i18n.TranslationHelper;

import java.util.UUID;

/**
 * The event side of clicking and chatting with shops (D9, D10): remove-mode and, later, pending trades.
 * Registers its events once at start-up; they do nothing until the server has started and the feature's
 * state exists.
 */
final class ShopClickHandler {

    private final ShopV2Feature feature;

    ShopClickHandler(ShopV2Feature feature) {
        this.feature = feature;
    }

    void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> feature.removeMode().end(handler.getPlayer().getUUID()));
    }

    /**
     * Once a second, tells players whose remove-mode ran out (D10).
     */
    private void onTick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (UUID expired : feature.removeMode().removeExpired(System.currentTimeMillis())) {
            ServerPlayer player = server.getPlayerList().getPlayer(expired);
            if (player != null) {
                player.sendSystemMessage(TranslationHelper.translate("shop.command.remove_mode.expired"));
            }
        }
    }
}
