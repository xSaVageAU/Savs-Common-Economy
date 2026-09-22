package savage.commoneconomy.shopv2;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.shopv2.model.Shop;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps shop signs in step with their containers (D9). Run it once a second: it takes the containers that changed
 * since the last run ({@link ContainerChanges}), finds which of them are part of a shop, whichever half of a double
 * chest changed, and rewrites each such shop's sign once, however many times it changed.
 */
final class SignRefresh {

    private final ShopV2Feature feature;
    private final FailedChecks failed = new FailedChecks();

    SignRefresh(ShopV2Feature feature) {
        this.feature = feature;
    }

    void run(MinecraftServer server) {
        Map<ResourceKey<Level>, LongOpenHashSet> changed = ContainerChanges.drain();
        if (changed.isEmpty() || feature.shops() == null) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> entry : changed.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            Set<Shop> refreshed = new HashSet<>();
            for (long packed : entry.getValue()) {
                Shop shop = feature.shops().findByContainerBlock(level, BlockPos.of(packed));
                if (shop != null && refreshed.add(shop)) {
                    // A broken shop must not stop the others' signs from refreshing, or crash the server
                    try {
                        feature.signs().refresh(level, shop, feature.shops().item(shop.id()));
                        failed.clear(shop.id());
                    } catch (RuntimeException e) {
                        if (failed.shouldReport(shop.id())) {
                            SavsCommonEconomy.LOGGER.error("Shop v2: refreshing the sign of the shop of {} at {} in {} failed; it will be skipped until it works again.",
                                    shop.ownerName(), shop.anchor().position(), shop.anchor().dimension(), e);
                        }
                    }
                }
            }
        }
    }
}
