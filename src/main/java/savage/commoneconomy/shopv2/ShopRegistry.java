package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import savage.commoneconomy.shopv2.model.BlockLocation;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Shop;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The shops in memory, looked up by identity: the dimension plus the anchor block (D1).
 * Call it from the server thread only.
 */
public final class ShopRegistry {

    private final Map<BlockLocation, Shop> byAnchor = new HashMap<>();

    /**
     * @return false, and does nothing, if another shop already has that anchor
     */
    public boolean add(Shop shop) {
        return byAnchor.putIfAbsent(shop.anchor(), shop) == null;
    }

    public Shop get(BlockLocation anchor) {
        return byAnchor.get(anchor);
    }

    public Collection<Shop> all() {
        return byAnchor.values();
    }

    public int size() {
        return byAnchor.size();
    }

    /**
     * "Is this block part of a shop?" (D3). A double chest is one container, so a shop covers both halves
     * whichever half it was created on. Does not load a chunk.
     *
     * @return the shop, or null if neither this block nor its partner half is a shop's anchor
     */
    public Shop findByContainerBlock(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        String dimension = level.dimension().identifier().toString();
        Shop shop = get(new BlockLocation(dimension, position(pos)));
        if (shop != null) {
            return shop;
        }
        BlockState state = level.getBlockState(pos);
        BlockPos partner = ContainerRegistry.partnerOf(state, pos);
        return partner == null ? null : get(new BlockLocation(dimension, position(partner)));
    }

    private static Position position(BlockPos pos) {
        return new Position(pos.getX(), pos.getY(), pos.getZ());
    }
}
