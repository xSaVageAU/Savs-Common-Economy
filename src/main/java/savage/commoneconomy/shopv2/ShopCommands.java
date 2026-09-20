package savage.commoneconomy.shopv2;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.shopv2.model.BlockLocation;
import savage.commoneconomy.shopv2.model.Prices;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopMode;
import savage.commoneconomy.shopv2.model.ShopStatus;
import savage.commoneconomy.shopv2.model.ShopType;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The /shop commands (D10). Same commands and permission nodes as v1.
 * Create, info, list and admin so far; resign and remove follow.
 */
final class ShopCommands {

    private final ShopV2Feature feature;

    ShopCommands(ShopV2Feature feature) {
        this.feature = feature;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
                .then(Commands.literal("create")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.create", true))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, ShopMode.SELL))))
                        .then(Commands.literal("buy")
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> createShop(ctx, ShopMode.BUY)))))
                .then(Commands.literal("info")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.info", true))
                        .executes(this::shopInfo))
                .then(Commands.literal("list")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.list", true))
                        .executes(this::listShops))
                .then(Commands.literal("admin")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                        .executes(this::makeAdmin)));
    }

    /**
     * Nothing is changed until every check has passed (D1, D4, D5, D10).
     * Then the item file and shops.json are written (D7), and only then is the sign placed; if the sign cannot be
     * placed after all, the shop is removed again (D5).
     */
    private int createShop(CommandContext<CommandSourceStack> context, ShopMode mode) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(TranslationHelper.translate("shop.command.hold_item"));
            return 0;
        }

        BlockHitResult blockHit = aimedBlock(player);
        if (blockHit == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.look_at_chest"));
            return 0;
        }
        ServerLevel level = player.level();
        BlockPos pos = blockHit.getBlockPos();

        if (feature.containers().resolve(level, pos) == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.look_at_chest_or_container"));
            return 0;
        }
        if (feature.shops().findByContainerBlock(level, pos) != null) {
            source.sendFailure(TranslationHelper.translate("shop.command.container_in_use"));
            return 0;
        }

        BigDecimal price = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "price"));
        if (Prices.hasTooManyDecimals(price)) {
            source.sendFailure(TranslationHelper.translate("shop.command.price_decimals"));
            return 0;
        }
        if (Prices.isTooHigh(price)) {
            source.sendFailure(TranslationHelper.translate("shop.command.price_too_high", EconomyService.get().format(Prices.MAX_PRICE)));
            return 0;
        }

        if (!mayUseContainer(player, level, blockHit)) {
            source.sendFailure(TranslationHelper.translate("shop.command.create_no_access"));
            return 0;
        }

        Direction side = ShopSigns.chooseSide(blockHit.getDirection(), player.getDirection());
        if (!ShopSigns.canPlace(level, pos, side)) {
            source.sendFailure(TranslationHelper.translate("shop.command.create_no_sign_space"));
            return 0;
        }

        Shop shop = new Shop(UUID.randomUUID(),
                new BlockLocation(level.dimension().identifier().toString(), Positions.toPosition(pos)),
                player.getUUID(), player.getName().getString(), ShopType.PLAYER, mode, price,
                Positions.toPosition(pos.relative(side)), false);
        try {
            feature.changes().create(shop, held);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not save the new shop at {} in {}", pos.toShortString(), shop.anchor().dimension(), e);
            source.sendFailure(TranslationHelper.translate("shop.command.create_failed"));
            return 0;
        }

        if (ShopSigns.place(level, pos, side) == null) {
            removeAfterFailedSign(shop);
            source.sendFailure(TranslationHelper.translate("shop.command.create_no_sign_space"));
            return 0;
        }
        feature.signs().refresh(level, shop, feature.shops().item(shop.id()));
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.create_success"), false);
        return 1;
    }

    private void removeAfterFailedSign(Shop shop) {
        try {
            feature.changes().remove(shop);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: shop {} was created but its sign could not be placed, and the shop could not be removed again.", shop.id(), e);
        }
    }

    /**
     * Fires the normal block-interaction event for the player and the aimed container and lets any mod that
     * uses that event refuse (D4). Only mods that use that event are consulted.
     */
    private static boolean mayUseContainer(ServerPlayer player, ServerLevel level, BlockHitResult hit) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit) == InteractionResult.PASS;
    }

    private int shopInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockHitResult blockHit = aimedBlock(player);
        if (blockHit == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.look_at_sign_or_chest"));
            return 0;
        }

        ServerLevel level = player.level();
        Shop shop = findShop(level, blockHit.getBlockPos());
        if (shop == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.no_shop_found"));
            return 0;
        }

        ItemStack item = feature.shops().item(shop.id());
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.header"), false);
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.owner", shop.ownerName()), false);
        if (item != null) {
            source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.item", item.getHoverName()), false);
        }
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.price", EconomyService.get().format(shop.price())), false);
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.type",
                TranslationHelper.translateString(shop.mode() == ShopMode.BUY ? "shop.action.verb_buy" : "shop.action.verb_sell")), false);
        Object stock = liveStock(level, shop, item);
        if (stock != null) {
            source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.stock", stock), false);
        }
        ShopStatus status = feature.health().statusOf(source.getServer(), shop);
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.info.status", TranslationHelper.translateString(status.languageKey())), false);
        return 1;
    }

    private int listShops(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        List<Shop> mine = feature.shops().ownedBy(player.getUUID());

        if (mine.isEmpty()) {
            source.sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.none"), false);
            return 1;
        }

        source.sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.header"), false);
        for (Shop shop : mine) {
            ItemStack item = feature.shops().item(shop.id());
            Object itemName = item == null ? "?" : item.getHoverName();
            String position = Positions.toBlockPos(shop.anchor().position()).toShortString();
            String status = TranslationHelper.translateString(feature.health().statusOf(source.getServer(), shop).languageKey());
            source.sendSuccess(() -> TranslationHelper.translate("shop.command.my_shops.entry_detail",
                    itemName, position, shop.anchor().dimension(), status), false);
        }
        return 1;
    }

    /**
     * Converts the shop the player is aiming at, by its container or its sign, into an admin shop. One way, as in v1.
     */
    private int makeAdmin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockHitResult blockHit = aimedBlock(player);
        if (blockHit == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.look_at_sign_or_chest"));
            return 0;
        }

        ServerLevel level = player.level();
        Shop shop = findShop(level, blockHit.getBlockPos());
        if (shop == null) {
            source.sendFailure(TranslationHelper.translate("shop.command.no_shop_found"));
            return 0;
        }

        Shop converted = shop.withType(ShopType.ADMIN);
        try {
            feature.changes().update(converted);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not save shop {} after converting it to an admin shop", shop.id(), e);
            source.sendFailure(TranslationHelper.translate("shop.command.save_failed"));
            return 0;
        }
        feature.signs().refresh(level, converted, feature.shops().item(shop.id()));
        source.sendSuccess(() -> TranslationHelper.translate("shop.command.admin_convert"), true);
        return 1;
    }

    /**
     * @return the block the player is aiming at within 5 blocks, or null if they are aiming at nothing
     */
    private static BlockHitResult aimedBlock(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0.0f, false);
        return hit.getType() == HitResult.Type.BLOCK ? (BlockHitResult) hit : null;
    }

    /**
     * A shop is found by either of its blocks: the container (both halves of a double chest) or its recorded sign.
     */
    private Shop findShop(ServerLevel level, BlockPos pos) {
        Shop shop = feature.shops().findByContainerBlock(level, pos);
        return shop != null ? shop : feature.shops().findBySign(level, pos);
    }

    /**
     * Stock is calculated when asked, never stored (D7).
     *
     * @return the text "Unlimited" for an admin shop, the item count (or the room for it in a shop that buys), or
     *         null if the item is unreadable or the container cannot be reached
     */
    private Object liveStock(ServerLevel level, Shop shop, ItemStack item) {
        if (shop.type() == ShopType.ADMIN) {
            return TranslationHelper.translateString("shop.command.info.stock.unlimited");
        }
        Container container = feature.containers().resolve(level, Positions.toBlockPos(shop.anchor().position()));
        if (item == null || container == null) {
            return null;
        }
        return shop.mode() == ShopMode.BUY ? ContainerStock.space(container, item) : ContainerStock.count(container, item);
    }
}
