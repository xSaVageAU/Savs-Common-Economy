package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopStatus;

/**
 * Works out a shop's status (D8). Only the on-use part exists so far; the chunk-load check, the periodic sweep,
 * reporting and automatic removal come with M8.
 * Nothing here loads a chunk: the container is only checked when its chunk is already loaded.
 */
public final class ShopHealth {

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
        return ShopStatus.of(shops.item(shop.id()) != null, containerEnabled(level, shop), signIntact(level, shop));
    }

    /**
     * A container in an unloaded chunk cannot be checked without loading it, so it counts as enabled.
     */
    private boolean containerEnabled(ServerLevel level, Shop shop) {
        BlockPos pos = Positions.toBlockPos(shop.anchor().position());
        return !level.hasChunkAt(pos) || containers.isAllowed(level.getBlockState(pos));
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

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
