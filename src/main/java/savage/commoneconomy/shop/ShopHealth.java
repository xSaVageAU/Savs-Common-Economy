package savage.commoneconomy.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;
import savage.commoneconomy.shop.model.Shop;
import savage.commoneconomy.shop.model.ShopStatus;

/**
 * Works out a shop's status (D8). The checks that act on it, the sweep, reporting and automatic removal, are in
 * {@code ShopChecker}. Nothing here loads a chunk: a container or sign is only looked at when its chunk is
 * already loaded.
 */
public final class ShopHealth {

    /** What is at a shop's anchor block right now. */
    enum ContainerState {
        /** The chunk is not loaded, so nothing can be said without loading it. */
        UNKNOWN,
        /** A container of an allowed type. */
        PRESENT,
        /** Still a container, but its type is no longer in shopAllowedContainers (the shop is Disabled). */
        NOT_ALLOWED,
        /** Not a container at all, for example air. */
        GONE
    }

    private final ContainerRegistry containers;
    private final ShopRegistry shops;

    public ShopHealth(ContainerRegistry containers, ShopRegistry shops) {
        this.containers = containers;
        this.shops = shops;
    }

    public ShopStatus statusOf(MinecraftServer server, Shop shop) {
        ServerLevel level = levelOf(server, shop.anchor().dimension());
        if (level == null) {
            return ShopStatus.of(shops.item(shop.id()) != null, false, true);
        }
        ContainerState container = containerStateOf(level, shop);
        boolean enabled = container == ContainerState.UNKNOWN || container == ContainerState.PRESENT;
        return ShopStatus.of(shops.item(shop.id()) != null, enabled, signIntact(level, shop));
    }

    /**
     * A block that is not in the allowed list but still holds an inventory is a container that was taken off the
     * list, which disables the shop. Only a block with no inventory at all counts as gone (D8), because that is the
     * one case where the shop is deleted.
     */
    ContainerState containerStateOf(ServerLevel level, Shop shop) {
        BlockPos pos = Positions.toBlockPos(shop.anchor().position());
        if (!level.hasChunkAt(pos)) {
            return ContainerState.UNKNOWN;
        }
        BlockState state = level.getBlockState(pos);
        if (containers.isAllowed(state)) {
            return ContainerState.PRESENT;
        }
        return level.getBlockEntity(pos) instanceof Container ? ContainerState.NOT_ALLOWED : ContainerState.GONE;
    }

    /**
     * The recorded sign must still be a sign block. One in an unloaded chunk cannot be checked without
     * loading it, so it counts as there.
     */
    private static boolean signIntact(ServerLevel level, Shop shop) {
        if (!shop.hasSign()) {
            return false;
        }
        return !level.hasChunkAt(Positions.toBlockPos(shop.sign())) || ShopSigns.isPresent(level, shop.sign());
    }

    /**
     * @return the level for a dimension identifier, or null if that dimension does not exist
     */
    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
