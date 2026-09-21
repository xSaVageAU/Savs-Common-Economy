package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.shopv2.model.Shop;

import java.io.IOException;

/**
 * Keeps the owner name cached in each shop up to date (D5). A shop stores its owner's name so the sign can show it
 * without a lookup, but a player can be renamed, so the next time an owner joins their shops are corrected, saved
 * right away (D7), and their signs rewritten. Most joins find nothing to change.
 */
final class OwnerNames {

    private final ShopV2Feature feature;

    OwnerNames(ShopV2Feature feature) {
        this.feature = feature;
    }

    void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refresh(server, handler.getPlayer()));
    }

    private void refresh(MinecraftServer server, ServerPlayer player) {
        if (feature.shops() == null) {
            return;
        }
        String name = player.getName().getString();
        String previousName = null;
        int updatedCount = 0;

        for (Shop shop : feature.shops().ownedBy(player.getUUID())) {
            if (shop.ownerName().equals(name)) {
                continue;
            }
            Shop updated = shop.withOwnerName(name);
            try {
                feature.changes().update(updated);
            } catch (IOException e) {
                // The old name is back in memory, so the next time this player joins it is tried again
                SavsCommonEconomy.LOGGER.error("Shop v2: could not save the new owner name of shop {}; it will be tried again at the next join.", shop.id(), e);
                return;
            }
            previousName = shop.ownerName();
            updatedCount++;

            // A sign in a chunk that is not loaded is not touched; it is corrected when the chunk loads
            ServerLevel level = ShopHealth.levelOf(server, shop.anchor().dimension());
            if (level != null) {
                feature.signs().refresh(level, updated, feature.shops().item(updated.id()));
            }
        }

        if (updatedCount > 0) {
            SavsCommonEconomy.LOGGER.info("Shop v2: {} is now called {}; updated {} shop(s).", previousName, name, updatedCount);
        }
    }
}
