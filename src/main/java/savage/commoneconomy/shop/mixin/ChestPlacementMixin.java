package savage.commoneconomy.shop.mixin;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import savage.commoneconomy.shop.ChestMergeRule;

/**
 * Applies the merge rule (D4) to the state a chest gets when it is placed. Trapped chests use this method as is, and
 * copper chests call it before adjusting their oxidation, so all of them are covered. The rule does nothing unless
 * chest shops are enabled.
 */
@Mixin(ChestBlock.class)
public abstract class ChestPlacementMixin {

    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void shop$keepOthersChestsSingle(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state != null) {
            cir.setReturnValue(ChestMergeRule.apply(context, state));
        }
    }
}
