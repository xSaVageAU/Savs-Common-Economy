package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.shopv2.model.Shop;

/**
 * The merge rule (D4): a chest placed next to someone else's shop chest comes out as a normal single chest instead of
 * joining it, so nobody can attach a chest to another player's shop to get at its contents. The owner placing a chest
 * next to their own shop chest merges normally, which extends the shop. The chest is still placed; it just does not join.
 *
 * The chest placement mixin calls this. It does nothing until the shops have started, so it has no effect when chest shops are off.
 */
public final class ChestMergeRule {

    private static ShopRegistry shops;

    private ChestMergeRule() {}

    /**
     * Starts applying the rule to the shops in this registry. Called when the server starts with chest shops enabled.
     */
    static void attach(ShopRegistry registry) {
        shops = registry;
    }

    /**
     * @param state the state vanilla chose for the chest being placed
     * @return the same state, or the chest as a single one if it would have joined another player's shop chest
     */
    public static BlockState apply(BlockPlaceContext context, BlockState state) {
        if (shops == null || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE || !(context.getLevel() instanceof ServerLevel level)) {
            return state;
        }
        // The chest it would join, which is a single chest at this moment, so it is the anchor of any shop there
        BlockPos partner = ChestBlock.getConnectedBlockPos(context.getClickedPos(), state);
        Shop shop = shops.findByContainerBlock(level, partner);
        if (shop == null) {
            return state;
        }
        Player player = context.getPlayer();
        if (player != null && shop.owner().equals(player.getUUID())) {
            return state;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.protect.no_merge"));
        }
        // Facing as a lone chest would get it, not the neighbour's facing that vanilla adopted for the merge
        return state.setValue(ChestBlock.TYPE, ChestType.SINGLE).setValue(ChestBlock.FACING, context.getHorizontalDirection().getOpposite());
    }
}
