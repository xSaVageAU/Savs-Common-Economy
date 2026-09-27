package savage.commoneconomy.shopv2;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers which containers changed since the last batch, so the signs of shops in them can be refreshed (D9).
 * The container mixin calls {@link #mark} every time a block entity reports a change, from players, hoppers and
 * anything else, so it does nothing but note the position. Whether a position is part of a shop is worked out later,
 * once a second, in the batch.
 *
 * Only changes on the server thread are noted, which also leaves out block entities touched by world generation.
 * Nothing is noted unless chest shops are enabled.
 */
public final class ContainerChanges {

    private static boolean active;
    private static Map<ResourceKey<Level>, LongOpenHashSet> changed = new HashMap<>();

    private ContainerChanges() {}

    /**
     * Starts noting changes. Called once when chest shops are enabled, so a server with them off pays nothing.
     */
    static void activate() {
        active = true;
    }

    /**
     * Called by the container mixin whenever a block entity reports a change.
     */
    public static void mark(BlockEntity blockEntity) {
        if (!active || !(blockEntity instanceof Container)) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        changed.computeIfAbsent(level.dimension(), dimension -> new LongOpenHashSet()).add(blockEntity.getBlockPos().asLong());
    }

    /**
     * @return the positions noted since the last call, per dimension, which are then forgotten
     */
    static Map<ResourceKey<Level>, LongOpenHashSet> drain() {
        Map<ResourceKey<Level>, LongOpenHashSet> drained = changed;
        changed = new HashMap<>();
        return drained;
    }
}
