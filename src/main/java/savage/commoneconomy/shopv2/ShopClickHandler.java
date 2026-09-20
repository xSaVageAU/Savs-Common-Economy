package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.shopv2.model.Shop;

import java.io.IOException;
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
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (feature.shops() == null || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            Shop shop = feature.shops().findByBlock(serverLevel, hit.getBlockPos());
            if (shop == null) {
                return InteractionResult.PASS;
            }
            if (feature.removeMode().isActive(serverPlayer.getUUID(), System.currentTimeMillis())) {
                return removeClickedShop(serverPlayer, serverLevel, shop);
            }
            return InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> feature.removeMode().end(handler.getPlayer().getUUID()));
    }

    /**
     * A click on a shop's sign or container while in remove-mode (D5, D10). The owner or an admin removes the shop
     * and its sign; anyone else is refused. Either way the mode ends, as in v1.
     */
    private InteractionResult removeClickedShop(ServerPlayer player, ServerLevel level, Shop shop) {
        feature.removeMode().end(player.getUUID());
        boolean allowed = shop.owner().equals(player.getUUID()) || PermissionsHelper.check(player, "savscommoneconomy.admin", 2);
        if (!allowed) {
            player.sendSystemMessage(TranslationHelper.translate("shop.remove.not_owner"));
            return InteractionResult.FAIL;
        }

        try {
            feature.changes().remove(shop);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not save shops.json after removing shop {}", shop.id(), e);
            player.sendSystemMessage(TranslationHelper.translate("shop.command.save_failed"));
            return InteractionResult.FAIL;
        }
        if (shop.hasSign()) {
            ShopSigns.clear(level, shop.sign());
        }
        player.sendSystemMessage(TranslationHelper.translate("shop.remove.success"));
        return InteractionResult.SUCCESS;
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
