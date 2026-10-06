package savage.commoneconomy.shop;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.shop.ShopHealth.ContainerState;
import savage.commoneconomy.shop.model.Position;
import savage.commoneconomy.shop.model.Shop;
import savage.commoneconomy.shop.model.ShopStatus;
import savage.commoneconomy.shop.storage.ShopItemStore;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The checks that keep shops honest (D8). A shop is checked when it is used, when its chunk loads, and by a sweep of
 * the shops whose chunks are already loaded, so anything that changed while it was unloaded, or changed by an
 * explosion, a command or another mod, is caught without a hook for each. None of them ever loads a chunk.
 *
 * A check does three things for a shop whose chunk is loaded:
 * <ul>
 *   <li>finds the sign of an imported shop that never recorded one;
 *   <li>deletes the shop if its container is gone, after seeing it missing twice ({@link MissingContainers});
 *   <li>reports a change of status once ({@link ReportedStatuses}): to the console, and to the owner if online.
 * </ul>
 *
 * A shop that throws while it is checked is logged once and skipped, not let through to crash the server
 * ({@link FailedChecks}).
 */
final class ShopChecker {

    private final ShopV2Feature feature;
    private final MissingContainers missing = new MissingContainers();
    private final ReportedStatuses reported = new ReportedStatuses();
    private final FailedChecks failed = new FailedChecks();
    private Map<ResourceKey<Level>, LongOpenHashSet> loadedChunks = new HashMap<>();

    ShopChecker(ShopV2Feature feature) {
        this.feature = feature;
    }

    /**
     * Notes each chunk that loads. The shops in it are checked at the next once-a-second run, together with any others
     * that loaded, so a burst of chunks costs one pass over the shops and not one each.
     */
    void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) -> {
            if (level.getServer().isSameThread()) {
                loadedChunks.computeIfAbsent(level.dimension(), dimension -> new LongOpenHashSet()).add(chunk.getPos().pack());
            }
        });
    }

    /**
     * Checks the shops in the chunks that loaded since the last run, and regenerates their signs. A sign is a display
     * that can always be regenerated (D5), so this catches anything that changed while the chunk was unloaded, such as
     * an owner who was renamed. A sign that already shows the right text is left alone.
     */
    void checkLoadedChunks(MinecraftServer server) {
        Map<ResourceKey<Level>, LongOpenHashSet> chunks = loadedChunks;
        loadedChunks = new HashMap<>();
        if (chunks.isEmpty() || feature.shops() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Shop shop : List.copyOf(feature.shops().all())) {
            ServerLevel level = ShopHealth.levelOf(server, shop.anchor().dimension());
            LongOpenHashSet inDimension = level == null ? null : chunks.get(level.dimension());
            if (inDimension != null && inDimension.contains(ChunkPos.pack(Positions.toBlockPos(shop.anchor().position())))) {
                safely(shop, () -> {
                    Shop checked = check(level, shop, now);
                    if (checked != null) {
                        feature.signs().refresh(level, checked, feature.shops().item(checked.id()));
                    }
                });
            }
        }
    }

    /**
     * Checks every shop whose chunk is loaded, and reports the shops whose dimension does not exist.
     */
    void sweep(MinecraftServer server) {
        if (feature.shops() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Shop shop : List.copyOf(feature.shops().all())) {
            ServerLevel level = ShopHealth.levelOf(server, shop.anchor().dimension());
            if (level == null) {
                safely(shop, () -> report(server, shop));
            } else if (level.hasChunkAt(Positions.toBlockPos(shop.anchor().position()))) {
                safely(shop, () -> check(level, shop, now));
            }
        }
    }

    /**
     * Runs one shop's periodic check. A broken shop must not stop the others from being checked, or let an
     * exception escape the tick and crash the server. The failure is logged once and not repeated while the same
     * shop keeps failing ({@link FailedChecks}).
     */
    private void safely(Shop shop, Runnable task) {
        try {
            task.run();
            failed.clear(shop.id());
        } catch (RuntimeException e) {
            if (failed.shouldReport(shop.id())) {
                SavsCommonEconomy.LOGGER.error("Shop v2: checking the shop of {} at {} in {} failed; it will be skipped until it works again.",
                        shop.ownerName(), describe(shop.anchor().position()), shop.anchor().dimension(), e);
            }
        }
    }

    /**
     * Checks a shop that is being used. The shop may be gone afterwards.
     *
     * @return the shop as it is now, or null if it was deleted
     */
    Shop checkNow(ServerLevel level, Shop shop) {
        return check(level, shop, System.currentTimeMillis());
    }

    /**
     * @return the shop as it is after the check, or null if it was deleted
     */
    private Shop check(ServerLevel level, Shop shop, long now) {
        ContainerState container = feature.health().containerStateOf(level, shop);
        if (container == ContainerState.UNKNOWN) {
            return shop;
        }
        if (container == ContainerState.GONE) {
            // Waiting for the second sighting: it is not reported as a status because it is about to be deleted
            if (missing.observeMissing(shop.id(), now)) {
                return delete(level, shop) ? null : shop;
            }
            return shop;
        }
        missing.observePresent(shop.id());

        Shop current = lookUpSign(level, shop);
        report(level.getServer(), current);
        return current;
    }

    /**
     * The deferred sign lookup for an imported shop (D5, D7): exactly one attached wall sign is recorded, several
     * record the first and log the rest, none clears the flag and the shop shows as having no sign. If part of the
     * search was in a chunk that is not loaded, nothing is decided and it is tried again at the next check.
     */
    private Shop lookUpSign(ServerLevel level, Shop shop) {
        if (!shop.needsSignLookup()) {
            return shop;
        }
        ShopSigns.AttachedSigns found = ShopSigns.findAttached(level, Positions.toBlockPos(shop.anchor().position()));
        // A sign that another shop has recorded is not this shop's to take
        List<Position> free = found.signs().stream().filter(sign -> {
            Shop owner = feature.shops().findBySign(level, Positions.toBlockPos(sign));
            return owner == null || owner.id().equals(shop.id());
        }).toList();

        Shop updated;
        if (!free.isEmpty()) {
            updated = shop.withSign(free.get(0)).withNeedsSignLookup(false);
        } else if (found.complete()) {
            updated = shop.withNeedsSignLookup(false);
        } else {
            return shop;
        }

        try {
            feature.changes().update(updated);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not save the sign lookup of shop {}; it will be tried again.", shop.id(), e);
            return shop;
        }
        if (free.size() > 1) {
            SavsCommonEconomy.LOGGER.warn("Shop v2: shop {} at {} in {} has {} signs attached; the first at {} was recorded and the others were left alone: {}.",
                    shop.id(), describe(shop.anchor().position()), shop.anchor().dimension(), free.size(), describe(free.get(0)),
                    free.subList(1, free.size()).stream().map(ShopChecker::describe).toList());
        }
        if (updated.hasSign()) {
            feature.signs().refresh(level, updated, feature.shops().item(updated.id()));
        }
        return updated;
    }

    /**
     * Reports a change of status once (D8): a console warning naming the owner and position, and a message to the
     * owner if they are online and the problem is a lost sign. A status that goes back to OK is only logged.
     */
    private void report(MinecraftServer server, Shop shop) {
        ShopStatus status = feature.health().statusOf(server, shop);
        if (!reported.changed(shop.id(), status)) {
            return;
        }
        String where = describe(shop.anchor().position()) + " in " + shop.anchor().dimension();
        if (status == ShopStatus.OK) {
            SavsCommonEconomy.LOGGER.info("Shop v2: the shop of {} at {} is OK again.", shop.ownerName(), where);
            return;
        }
        SavsCommonEconomy.LOGGER.warn("Shop v2: the shop of {} ({}) at {} is now {}.", shop.ownerName(), shop.owner(), where, status);
        if (status == ShopStatus.NO_SIGN) {
            tell(server, shop, TranslationHelper.translate("shop.notice.sign_missing", describe(shop.anchor().position())));
        }
    }

    /**
     * Deletes a shop whose container is gone (D8). It cannot be undone, so the whole record is written to the log first,
     * including the item as SNBT, so it can be restored by hand. The owner is told if online.
     *
     * @return false if the shop could not be deleted, in which case it is tried again at the next check
     */
    private boolean delete(ServerLevel level, Shop shop) {
        ItemStack item = feature.shops().item(shop.id());
        String itemText = item == null ? "unreadable" : ShopItemStore.toSnbt(item, level.getServer().registryAccess());
        SavsCommonEconomy.LOGGER.warn("Shop v2: deleting shop {} because its container is gone. Record: owner {} ({}), dimension {}, container at {}, "
                + "type {}, mode {}, price {}, sign {}, item {}", shop.id(), shop.ownerName(), shop.owner(), shop.anchor().dimension(),
                describe(shop.anchor().position()), shop.type(), shop.mode(), shop.price().toPlainString(),
                shop.hasSign() ? describe(shop.sign()) : "none", itemText);

        try {
            feature.changes().remove(shop);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not delete shop {}; it will be tried again.", shop.id(), e);
            return false;
        }
        if (shop.hasSign()) {
            ShopSigns.clear(level, shop.sign());
        }
        missing.forget(shop.id());
        reported.forget(shop.id());
        tell(level.getServer(), shop, TranslationHelper.translate("shop.notice.removed_container_gone", describe(shop.anchor().position())));
        return true;
    }

    private static void tell(MinecraftServer server, Shop shop, Component message) {
        ServerPlayer owner = server.getPlayerList().getPlayer(shop.owner());
        if (owner != null) {
            owner.sendSystemMessage(message);
        }
    }

    private static String describe(Position position) {
        return Positions.toBlockPos(position).toShortString();
    }
}
