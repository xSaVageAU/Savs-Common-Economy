package savage.commoneconomy.shop;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.shop.model.Shop;

import java.io.IOException;

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
        PlayerBlockBreakEvents.BEFORE.register(this::onBreak);
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

    /**
     * Nobody, the owner included, can break a shop's container; the owner removes the shop by breaking its sign or
     * with /shop remove. Breaking a shop's sign removes the shop for its owner or an admin and is refused for anyone
     * else. Either way vanilla's break is cancelled: the sign was placed for free, so it is cleared without a drop.
     *
     * @return false to cancel the break
     */
    private boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (feature.shops() == null || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        if (feature.shops().findByContainerBlock(serverLevel, pos) != null) {
            serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.protect.break_chest"));
            return false;
        }
        Shop shop = feature.shops().findBySign(serverLevel, pos);
        if (shop == null) {
            return true;
        }
        if (!mayManage(serverPlayer, shop)) {
            serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.protect.break_sign"));
            return false;
        }

        try {
            feature.changes().remove(shop);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not delete the file of shop {} after a player broke its sign, so it was not removed", shop.id(), e);
            serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.command.save_failed"));
            return false;
        }
        ShopSigns.clear(serverLevel, shop.sign());
        serverPlayer.sendSystemMessage(TranslationHelper.translate("shop.remove.sign_broken"));
        return false;
    }
}
