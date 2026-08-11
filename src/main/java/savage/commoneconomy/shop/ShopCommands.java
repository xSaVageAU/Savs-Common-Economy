package savage.commoneconomy.shop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.PermissionsHelper;
import savage.commoneconomy.util.TranslationHelper;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Commands for creating and managing shops.
 */
public class ShopCommands {
    private static final Set<UUID> removeModePlayers = new HashSet<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
                .then(Commands.literal("create")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.create", true))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, false))))
                        .then(Commands.literal("buy")
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, true)))))
                .then(Commands.literal("info")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.info", true))
                        .executes(ShopCommands::shopInfo))
                .then(Commands.literal("list")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.list", true))
                        .executes(ShopCommands::listShops))
                .then(Commands.literal("admin")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                        .executes(ShopCommands::makeAdmin))
                .then(Commands.literal("remove")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.remove", true))
                        .executes(ShopCommands::toggleRemoveMode)));
    }

    private static int createShop(CommandContext<CommandSourceStack> context, boolean buying) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BigDecimal price = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "price"));
        ItemStack heldItem = player.getMainHandItem();

        if (heldItem.isEmpty()) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.hold_item"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.look_at_chest"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockEntity be = player.level().getBlockEntity(pos);

        if (!(be instanceof Container)) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.look_at_chest_or_container"));
            return 0;
        }

        if (ShopManager.getInstance().getShop(pos) != null) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.shop_exists"));
            return 0;
        }

        String worldId = player.level().dimension().identifier().toString();
        Shop shop = ShopManager.getInstance().createShop(pos, worldId, player.getUUID(), player.getName().getString(), heldItem.copy(), price, buying, ShopType.PLAYER);

        if (ShopSignHelper.placeSign(player.level(), pos, shop, player.getDirection())) {
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.create_success"), false);
        } else {
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.create_sign_failed"), false);
        }

        return 1;
    }

    private static int shopInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.look_at_sign_or_chest"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Shop initialShop = ShopManager.getInstance().getShop(pos);
        if (initialShop == null) {
            BlockPos chestPos = ShopSignHelper.getAttachedChest(player.level(), pos);
            initialShop = ShopManager.getInstance().getShop(chestPos);
        }

        if (initialShop == null) {
            context.getSource().sendFailure(TranslationHelper.translate("shop.command.no_shop_found"));
            return 0;
        }

        final Shop shop = initialShop;

        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.header"), false);
        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.owner", shop.getOwnerName()), false);
        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.item", shop.getItem().getHoverName()), false);
        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.price", EconomyManager.getInstance().format(shop.getPrice())), false);
        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.type", (shop.isBuying() ? "Buy" : "Sell")), false);
        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.info.stock", (shop.isAdmin() ? "Unlimited" : shop.getStock())), false);

        return 1;
    }

    private static int listShops(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Collection<Shop> shops = ShopManager.getInstance().getPlayerShops(player.getUUID());

        if (shops.isEmpty()) {
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.none"), false);
            return 1;
        }

        context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.header"), false);
        for (Shop shop : shops) {
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.entry", shop.getItem().getHoverName(), shop.getChestLocation().toShortString()), false);
        }
        return 1;
    }

    private static int makeAdmin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return 0;
        
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Shop shop = ShopManager.getInstance().getShop(pos);
        if (shop != null) {
            shop.setType(ShopType.ADMIN);
            ShopManager.getInstance().save();
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.admin_convert"), true);
            ShopSignHelper.updateSign((net.minecraft.server.level.ServerLevel)player.level(), ShopSignHelper.findSignForChest(player.level(), pos), shop);
        }
        return 1;
    }

    private static int toggleRemoveMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID uuid = player.getUUID();
        if (removeModePlayers.contains(uuid)) {
            removeModePlayers.remove(uuid);
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.remove_mode.exit"), false);
        } else {
            removeModePlayers.add(uuid);
            context.getSource().sendSuccess(() -> TranslationHelper.translate("shop.command.remove_mode.enter"), false);
        }
        return 1;
    }

    public static boolean isInRemoveMode(UUID uuid) {
        return removeModePlayers.contains(uuid);
    }

    public static void exitRemoveMode(UUID uuid) {
        removeModePlayers.remove(uuid);
    }
}
