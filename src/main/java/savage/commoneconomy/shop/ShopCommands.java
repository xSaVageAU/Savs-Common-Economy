package savage.commoneconomy.shop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShopCommands {
    private static final Map<UUID, Boolean> removeMode = new HashMap<>();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("shop")
                .then(CommandManager.literal("create")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.shop.create", true))
                        .then(CommandManager.literal("sell")
                                .then(CommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, false))))
                        .then(CommandManager.literal("buy")
                                .then(CommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, true)))))
                .then(CommandManager.literal("remove")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.shop.remove", true))
                        .executes(ShopCommands::enterRemoveMode))
                .then(CommandManager.literal("info")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.shop.info", true))
                        .executes(ShopCommands::shopInfo))
                .then(CommandManager.literal("setprice")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.shop.create", true))
                        .then(CommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                                .executes(ShopCommands::setPrice)))
                .then(CommandManager.literal("admin")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                        .executes(ShopCommands::makeAdmin))
                .then(CommandManager.literal("list")
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.shop.list", true))
                        .executes(ShopCommands::listShops)));
    }

    private static int createShop(CommandContext<ServerCommandSource> context, boolean buying) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        double priceDouble = DoubleArgumentType.getDouble(context, "price");
        BigDecimal price = BigDecimal.valueOf(priceDouble);

        ItemStack heldItem = player.getMainHandStack();
        if (heldItem.isEmpty()) {
            context.getSource().sendError(Text.literal("You must hold an item to create a shop!"));
            return 0;
        }

        HitResult hitResult = player.raycast(5.0, 0.0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendError(Text.literal("You must be looking at a chest!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        
        if (!(context.getSource().getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.block.ChestBlock)) {
            context.getSource().sendError(Text.literal("You must be looking at a chest!"));
            return 0;
        }

        if (ShopManager.getInstance().isShopChest(pos)) {
            context.getSource().sendError(Text.literal("A shop already exists at this location!"));
            return 0;
        }

        String worldId = context.getSource().getWorld().getRegistryKey().getValue().toString();
        
        Shop shop = ShopManager.getInstance().createShop(
                pos,
                worldId,
                player.getUuid(),
                player.getName().getString(),
                heldItem.copy(),
                price,
                buying,
                ShopType.PLAYER
        );

        if (!ShopSignHelper.placeSign(context.getSource().getWorld(), pos, shop, player.getHorizontalFacing())) {
            context.getSource().sendError(Text.literal("Warning: Could not place sign! Shop created but no sign."));
        }

        String shopType = buying ? "buying" : "selling";
        context.getSource().sendFeedback(() -> Text.literal(
                "Shop created! " + shopType + " " + heldItem.getName().getString() + 
                " for " + EconomyManager.getInstance().format(price) + " each."), false);

        return 1;
    }

    private static int enterRemoveMode(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        removeMode.put(player.getUuid(), true);
        context.getSource().sendFeedback(() -> Text.literal(
                "Click on a shop sign to remove it. Run /shop remove again to cancel."), false);
        return 1;
    }

    public static boolean isInRemoveMode(UUID playerId) {
        return removeMode.getOrDefault(playerId, false);
    }

    public static void exitRemoveMode(UUID playerId) {
        removeMode.remove(playerId);
    }

    private static int shopInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        HitResult hitResult = player.raycast(5.0, 0.0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendError(Text.literal("You must be looking at a shop!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Shop shop = ShopManager.getInstance().getShop(pos);

        if (shop == null) {
            context.getSource().sendError(Text.literal("No shop found at this location!"));
            return 0;
        }

        String shopType = shop.isBuying() ? "Buying" : "Selling";
        String adminStatus = shop.isAdmin() ? " (Admin Shop)" : "";
        
        context.getSource().sendFeedback(() -> Text.literal("=== Shop Info ==="), false);
        context.getSource().sendFeedback(() -> Text.literal("Owner: " + shop.getOwnerName()), false);
        context.getSource().sendFeedback(() -> Text.literal("Type: " + shopType + adminStatus), false);
        context.getSource().sendFeedback(() -> Text.literal("Item: " + shop.getItem().getName().getString()), false);
        context.getSource().sendFeedback(() -> Text.literal("Price: " + EconomyManager.getInstance().format(shop.getPrice()) + " each"), false);
        
        if (!shop.isAdmin()) {
            context.getSource().sendFeedback(() -> Text.literal("Stock: " + shop.getStock()), false);
        } else {
            context.getSource().sendFeedback(() -> Text.literal("Stock: Unlimited"), false);
        }

        return 1;
    }

    private static int setPrice(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        double priceDouble = DoubleArgumentType.getDouble(context, "price");
        BigDecimal price = BigDecimal.valueOf(priceDouble);

        HitResult hitResult = player.raycast(5.0, 0.0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendError(Text.literal("You must be looking at a shop!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Shop shop = ShopManager.getInstance().getShop(pos);

        if (shop == null) {
            context.getSource().sendError(Text.literal("No shop found at this location!"));
            return 0;
        }

        if (!shop.getOwnerId().equals(player.getUuid()) && !savage.commoneconomy.util.PermissionsHelper.check(context.getSource(), "savscommoneconomy.admin", 2)) {
            context.getSource().sendError(Text.literal("You don't own this shop!"));
            return 0;
        }

        shop.setPrice(price);
        ShopManager.getInstance().save();

        context.getSource().sendFeedback(() -> Text.literal(
                "Shop price updated to " + EconomyManager.getInstance().format(price) + " each."), false);

        return 1;
    }

    private static int makeAdmin(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        HitResult hitResult = player.raycast(5.0, 0.0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendError(Text.literal("You must be looking at a shop!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Shop shop = ShopManager.getInstance().getShop(pos);

        if (shop == null) {
            context.getSource().sendError(Text.literal("No shop found at this location!"));
            return 0;
        }

        shop.setType(ShopType.ADMIN);
        ShopManager.getInstance().save();

        context.getSource().sendFeedback(() -> Text.literal(
                "Shop converted to admin shop (infinite stock)."), true);

        return 1;
    }

    private static int listShops(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        
        var shops = ShopManager.getInstance().getPlayerShops(player.getUuid());
        
        if (shops.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("You don't own any shops."), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("=== Your Shops ==="), false);
        for (Shop shop : shops) {
            BlockPos pos = shop.getChestLocation();
            String shopType = shop.isBuying() ? "Buying" : "Selling";
            String location = "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            
            context.getSource().sendFeedback(() -> Text.literal(
                    shopType + " " + shop.getItem().getName().getString() + 
                    " at " + location), false);
        }

        return 1;
    }
}
