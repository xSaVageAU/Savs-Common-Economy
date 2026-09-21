package savage.commoneconomy.shopv2;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.shopv2.model.Shop;

/**
 * What v2 protects (D4). It follows the shop record, whatever the shop's status, and covers both halves of a double
 * chest. Not protected, and left to claim and protection mods: hoppers, explosions, copper golems and other ways of
 * removing items.
 */
final class ShopProtection {

    private final ShopV2Feature feature;

    ShopProtection(ShopV2Feature feature) {
        this.feature = feature;
    }

    void register() {
        UseBlockCallback.EVENT.register(this::onUse);
    }

    /**
     * The owner or an admin may open the container and manage the shop.
     */
    static boolean mayManage(ServerPlayer player, Shop shop) {
        return shop.owner().equals(player.getUUID()) || PermissionsHelper.check(player, "savscommoneconomy.admin", 2);
    }

    /**
     * Only the owner and admins can open a shop's container. This applies to either hand: a modified client can send
     * an off-hand use straight to the server, so checking only the main hand would leave a way in.
     */
    private InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (feature.shops() == null || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        Shop shop = feature.shops().findByContainerBlock(serverLevel, hit.getBlockPos());
        if (shop == null || mayManage(serverPlayer, shop)) {
            return InteractionResult.PASS;
        }
        serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.protect.chest"));
        return InteractionResult.FAIL;
    }
}
