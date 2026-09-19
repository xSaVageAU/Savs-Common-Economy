package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.shopv2.model.Shop;

import java.util.function.Supplier;

/**
 * TEMPORARY, for checking M2 in the game. Logs what the registries make of every block right-clicked
 * with the main hand. Delete this file and its one call in ShopV2Feature once M2 is checked.
 */
final class TemporaryDebug {

    private TemporaryDebug() {}

    static void register(Supplier<ContainerRegistry> containers, Supplier<ShopRegistry> shops) {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level instanceof ServerLevel serverLevel && hand == InteractionHand.MAIN_HAND
                    && containers.get() != null && shops.get() != null) {
                log(serverLevel, hit.getBlockPos(), containers.get(), shops.get());
            }
            return InteractionResult.PASS;
        });
    }

    private static void log(ServerLevel level, BlockPos pos, ContainerRegistry containers, ShopRegistry shops) {
        BlockState state = level.getBlockState(pos);
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean allowed = containers.isAllowed(state);
        Container container = containers.resolve(level, pos);
        String inventory = container == null ? "none" : container.getContainerSize() + " slots, " + filledSlots(container) + " filled";
        BlockPos partner = ContainerRegistry.partnerOf(state, pos);
        Shop shop = shops.findByContainerBlock(level, pos);
        SavsCommonEconomy.LOGGER.info("[shopv2 debug] {} at {} | allowed={} | inventory={} | partner={} | shop={}",
                block, pos.toShortString(), allowed, inventory,
                partner == null ? "none" : partner.toShortString(),
                shop == null ? "none" : shop.id() + " anchor " + shop.anchor().position());
    }

    private static int filledSlots(Container container) {
        int filled = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                filled++;
            }
        }
        return filled;
    }
}
