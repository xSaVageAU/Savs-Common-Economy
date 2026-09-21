package savage.commoneconomy.shopv2.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import savage.commoneconomy.shopv2.ContainerChanges;

/**
 * Notes every block entity change so shop signs can follow their container (D9). Every container funnels its
 * changes through BlockEntity.setChanged(), which chests and barrels do not override, so one hook covers them all.
 * The hook only records the position and does nothing unless shop v2 is selected.
 */
@Mixin(BlockEntity.class)
public abstract class ContainerChangeMixin {

    // The descriptor picks the instance method; BlockEntity also has a static setChanged(Level, BlockPos, BlockState)
    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void shopv2$noteChange(CallbackInfo ci) {
        ContainerChanges.mark((BlockEntity) (Object) this);
    }
}
