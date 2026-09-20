package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import savage.commoneconomy.shopv2.model.BlockLocation;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Shop;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The shops in memory, looked up by identity: the dimension plus the anchor block (D1). Also holds each shop's item.
 * Call it from the server thread only.
 */
public final class ShopRegistry {

    private final Map<BlockLocation, Shop> byAnchor = new HashMap<>();
    private final Map<UUID, ItemStack> items = new HashMap<>();

    /**
     * @param item the shop's single-item template, or null if its item file could not be read (the shop is still kept)
     * @return false, and does nothing, if another shop already has that anchor
     */
    public boolean add(Shop shop, ItemStack item) {
        if (byAnchor.putIfAbsent(shop.anchor(), shop) != null) {
            return false;
        }
        if (item != null) {
            items.put(shop.id(), item);
        }
        return true;
    }

    /**
     * @return the shop's single-item template, or null if its item could not be read
     */
    public ItemStack item(UUID shopId) {
        return items.get(shopId);
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
