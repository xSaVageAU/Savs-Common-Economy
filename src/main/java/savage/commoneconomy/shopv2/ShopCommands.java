package savage.commoneconomy.shopv2;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopMode;
import savage.commoneconomy.shopv2.model.ShopStatus;
import savage.commoneconomy.shopv2.model.ShopType;

import java.util.List;

/**
 * The /shop commands (D10). Same commands and permission nodes as v1.
 * Only info and list so far; create, remove, resign and admin follow.
 */
final class ShopCommands {

    private final ShopV2Feature feature;

    ShopCommands(ShopV2Feature feature) {
        this.feature = feature;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
                .then(Commands.literal("info")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.info", true))
                        .executes(this::shopInfo))
                .then(Commands.literal("list")
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.shop.list", true))
                        .executes(this::listShops)));
    }

    private int shopInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(TranslationHelper.translate("shop.command.look_at_sign_or_chest"));
            return 0;
        }

        ServerLevel level = player.level();
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Shop shop = findShop(level, pos);
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
