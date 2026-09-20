package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.WallSignBlock;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Shop;

import java.util.function.Supplier;

/**
 * TEMPORARY, for checking M3 in the game. With a stick in the main hand:
 * right-click a shop's wall sign to rewrite its text from v2's generator, or right-click a shop's container to place
 * a v2 sign on the aimed side and write its text. Neither is saved. Delete this file and its one call in
 * ShopV2Feature once M3 is checked.
 */
final class TemporaryDebug {

    private TemporaryDebug() {}

    static void register(Supplier<ContainerRegistry> containers, Supplier<ShopRegistry> shops) {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(level instanceof ServerLevel serverLevel) || hand != InteractionHand.MAIN_HAND
                    || !player.getMainHandItem().is(Items.STICK) || containers.get() == null || shops.get() == null) {
                return InteractionResult.PASS;
            }
            ShopSigns signs = new ShopSigns(containers.get());
            BlockPos pos = hit.getBlockPos();

            if (serverLevel.getBlockState(pos).getBlock() instanceof WallSignBlock) {
                BlockPos attached = pos.relative(serverLevel.getBlockState(pos).getValue(WallSignBlock.FACING).getOpposite());
                Shop shop = shops.get().findByContainerBlock(serverLevel, attached);
                say(player, shop == null ? "That sign is not on a shop's container" : "Refreshing the sign of shop " + shop.id());
                if (shop != null) {
                    signs.refresh(serverLevel, shop.withSign(position(pos)), shops.get().item(shop.id()));
                }
                return InteractionResult.SUCCESS;
            }

            Shop shop = shops.get().findByContainerBlock(serverLevel, pos);
            if (shop == null) {
                return InteractionResult.PASS;
            }
            BlockPos anchor = new BlockPos(shop.anchor().position().x(), shop.anchor().position().y(), shop.anchor().position().z());
            Direction side = ShopSigns.chooseSide(hit.getDirection(), player.getDirection());
            Position placed = ShopSigns.place(serverLevel, anchor, side);
            say(player, "Aimed " + hit.getDirection() + ", facing " + player.getDirection() + " -> side " + side
                    + (placed == null ? ": cannot place a sign there" : ": placed at " + placed));
            ItemStack item = shops.get().item(shop.id());
            if (placed != null && item != null) {
                signs.refresh(serverLevel, shop.withSign(placed), item);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static Position position(BlockPos pos) {
        return new Position(pos.getX(), pos.getY(), pos.getZ());
    }

    private static void say(Player player, String text) {
        SavsCommonEconomy.LOGGER.info("[shopv2 debug] {}", text);
    }
}
