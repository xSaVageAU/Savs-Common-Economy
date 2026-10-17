package savage.commoneconomy.mixin;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import savage.commoneconomy.shop.ShopManager;

/**
 * Mixin on BlockEntity.setChanged() to detect when a chest's contents change
 * (player, hopper, dropper, etc.). Only acts on ChestBlockEntity instances.
 * Marks the shop as dirty so its sign gets updated on the next tick cycle.
 */
@Mixin(BlockEntity.class)
public abstract class ContainerBlockEntityMixin {

    @Inject(method = "setChanged", at = @At("TAIL"))
    private void onSetChanged(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self instanceof Container && self.getLevel() != null && !self.getLevel().isClientSide()) {
            BlockPos pos = self.getBlockPos();
            if (ShopManager.getInstance().isShopChest(pos)) {
                ShopManager.getInstance().markDirty(pos);
            }
        }
    }
}
