package savage.commoneconomy.shop;

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
import savage.commoneconomy.shop.model.Shop;
import savage.commoneconomy.shop.model.ShopMode;
import savage.commoneconomy.shop.model.ShopStatus;

import java.io.IOException;
import java.util.UUID;

/**
 * The event side of clicking shops (D9, D10): remove-mode and starting a trade, plus the once-a-second tick.
 * Registers its events once at start-up; they do nothing until the server has started and the feature's
 * state exists.
 */
final class ShopClickHandler {

    private final ShopV2Feature feature;
    private final SignRefresh signRefresh;

    ShopClickHandler(ShopV2Feature feature) {
        this.feature = feature;
        this.signRefresh = new SignRefresh(feature);
    }

    void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (feature.shops() == null || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            long now = System.currentTimeMillis();
            if (feature.removeMode().isActive(serverPlayer.getUUID(), now)) {
                Shop shop = feature.shops().findByBlock(serverLevel, hit.getBlockPos());
                return shop == null ? InteractionResult.PASS : removeClickedShop(serverPlayer, serverLevel, shop);
            }
            // Only the sign starts a trade; who may open the container is decided by ShopProtection
            Shop shop = feature.shops().findBySign(serverLevel, hit.getBlockPos());
            return shop == null ? InteractionResult.PASS : startTrade(serverPlayer, serverLevel, shop, now);
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID player = handler.getPlayer().getUUID();
            feature.removeMode().end(player);
            feature.pendingTrades().end(player);
        });
    }

    /**
     * A click on a shop's sign starts a trade (D9): the player is asked to type an amount in chat. A shop that is not
     * OK says it is unavailable and starts nothing. The click never places a block or opens the sign editor.
     */
    private InteractionResult startTrade(ServerPlayer player, ServerLevel level, Shop clicked, long now) {
        // The check that goes with using a shop (D8) may find that its container is gone and delete it
        Shop shop = feature.checker().checkNow(level, clicked);
        if (shop == null || feature.health().statusOf(level.getServer(), shop) != ShopStatus.OK) {
            player.sendSystemMessage(TranslationHelper.translate("shop.interaction.unavailable"));
            return InteractionResult.SUCCESS;
        }

        feature.pendingTrades().start(player.getUUID(), shop.anchor(), now);
        String action = TranslationHelper.translateString(shop.mode() == ShopMode.BUY ? "shop.action.verb_sell" : "shop.action.verb_buy");
        player.sendSystemMessage(TranslationHelper.translate("shop.interaction.prompt_amount", action));
        player.sendSystemMessage(TranslationHelper.translate("shop.interaction.prompt_all", action));
        return InteractionResult.SUCCESS;
    }

    /**
     * A click on a shop's sign or container while in remove-mode (D5, D10). The owner or an admin removes the shop
     * and its sign; anyone else is refused. Either way the mode ends, as in v1.
     */
    private InteractionResult removeClickedShop(ServerPlayer player, ServerLevel level, Shop clicked) {
        feature.removeMode().end(player.getUUID());
        if (!ShopProtection.mayManage(player, clicked)) {
            player.sendSystemMessage(TranslationHelper.translate("shop.remove.not_owner"));
            return InteractionResult.FAIL;
        }

        // The check that goes with using a shop (D8) runs first. It records the sign of an imported shop that has not
        // had it looked up yet, so the sign is cleared with the shop, and it deletes a shop whose container is gone.
        Shop shop = feature.checker().checkNow(level, clicked);
        if (shop == null) {
            player.sendSystemMessage(TranslationHelper.translate("shop.remove.success"));
            return InteractionResult.SUCCESS;
        }

        try {
            feature.changes().remove(shop);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not delete the file of shop {}, so it was not removed", shop.id(), e);
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
     * Once a second: refreshes the signs of shops whose containers changed (D9), checks the shops in chunks that
     * loaded (D8), and tells players whose remove-mode ran out (D10). Every fifth second it sweeps all loaded shops (D8).
     */
    private void onTick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        signRefresh.run(server);
        feature.checker().checkLoadedChunks(server);
        if (server.getTickCount() % 100 == 0) {
            feature.checker().sweep(server);
        }
        for (UUID expired : feature.removeMode().removeExpired(System.currentTimeMillis())) {
            ServerPlayer player = server.getPlayerList().getPlayer(expired);
            if (player != null) {
                player.sendSystemMessage(TranslationHelper.translate("shop.command.remove_mode.expired"));
            }
        }
    }
}
