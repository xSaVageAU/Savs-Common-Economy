package savage.commoneconomy.shop;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.inventory.InventorySpace;
import savage.commoneconomy.shop.model.Prices;
import savage.commoneconomy.shop.model.Shop;
import savage.commoneconomy.shop.model.ShopMode;
import savage.commoneconomy.shop.model.ShopStatus;
import savage.commoneconomy.shop.model.ShopType;

import java.util.OptionalInt;

/**
 * The chat side of a trade (D9): the amount a player types after clicking a shop's sign.
 * While a trade is pending, the next chat line is the amount and is not shown as chat.
 */
final class ShopChatHandler {

    /** How far from the shop's sign a player may be when the amount is submitted (D9). */
    private static final double MAX_DISTANCE = 16.0;

    /** "All" for a purchase never goes above this, which is 36 slots of 64. Inventory space already implies it. */
    private static final int MAX_ALL_PURCHASE = 2304;

    private final ShopFeature feature;

    ShopChatHandler(ShopFeature feature) {
        this.feature = feature;
    }

    void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !handle(sender, message.signedContent()));
    }

    /**
     * @return true if the line was the amount for a pending trade and must not be shown as chat
     */
    private boolean handle(ServerPlayer player, String typed) {
        if (feature.shops() == null) {
            return false;
        }
        // A pending trade that ran out is forgotten here, and the line goes out as ordinary chat
        PendingTrades.Pending pending = feature.pendingTrades().active(player.getUUID(), System.currentTimeMillis());
        if (pending == null) {
            return false;
        }
        // Whatever happens next, this pending trade is used up
        feature.pendingTrades().end(player.getUUID());

        String text = typed.trim();
        boolean all = text.equalsIgnoreCase("all");
        int amount = all ? 0 : parseAmount(text);
        if (!all && amount <= 0) {
            player.sendSystemMessage(TranslationHelper.translate("shop.interaction.invalid_amount"));
            return true;
        }

        ServerLevel level = player.level();
        Shop shop = feature.shops().get(pending.shop());
        if (shop == null || feature.health().statusOf(level.getServer(), shop) != ShopStatus.OK) {
            player.sendSystemMessage(TranslationHelper.translate("shop.interaction.unavailable"));
            return true;
        }
        if (!isInRange(player, level, shop)) {
            player.sendSystemMessage(TranslationHelper.translate("shop.interaction.too_far"));
            return true;
        }

        ItemStack item = feature.shops().item(shop.id());
        if (all) {
            OptionalInt everything = amountForAll(player, level, shop, item);
            if (everything.isEmpty()) {
                player.sendSystemMessage(TranslationHelper.translate("shop.interaction.admin_infinite"));
                return true;
            }
            amount = everything.getAsInt();
        }
        feature.trades().start(player, shop, amount);
        return true;
    }

    /**
     * @return the amount, or 0 if the text is not a whole number above zero
     */
    private static int parseAmount(String text) {
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The player must still be in the shop's dimension and near its sign, so a trade never reaches a far-away
     * chunk (D9).
     */
    private static boolean isInRange(ServerPlayer player, ServerLevel level, Shop shop) {
        if (!level.dimension().identifier().toString().equals(shop.anchor().dimension())) {
            return false;
        }
        Vec3 sign = Vec3.atCenterOf(Positions.toBlockPos(shop.sign()));
        return player.position().distanceToSqr(sign) <= MAX_DISTANCE * MAX_DISTANCE;
    }

    /**
     * What "all" means (D9). A purchase is limited by what the buyer can afford, what the shop has and what fits in
     * the buyer's inventory. A sale is limited by what the seller has and, in a player shop, by the shop's free
     * space and by what the owner can pay for. A balance that is not known sets no limit, and the real charge can
     * still fail.
     *
     * @return the amount, or empty if "all" is refused: an admin shop has infinite stock to buy
     */
    private OptionalInt amountForAll(ServerPlayer player, ServerLevel level, Shop shop, ItemStack item) {
        boolean admin = shop.type() == ShopType.ADMIN;
        Container container = admin ? null : feature.containers().resolve(level, Positions.toBlockPos(shop.anchor().position()));

        if (shop.mode() == ShopMode.SELL) {
            if (admin) {
                return OptionalInt.empty();
            }
            int inStock = container == null ? 0 : ContainerStock.count(container, item);
            int affordable = Prices.affordable(EconomyService.get().peekBalance(player.getUUID()), shop.price());
            int fits = InventorySpace.getAvailableSpace(player, item);
            return OptionalInt.of(Math.min(Math.min(affordable, inStock), Math.min(fits, MAX_ALL_PURCHASE)));
        }

        int carried = PlayerItems.count(player, item);
        if (admin) {
            return OptionalInt.of(carried);
        }
        int room = container == null ? 0 : ContainerStock.space(container, item);
        int ownerCanPay = Prices.affordable(EconomyService.get().peekBalance(shop.owner()), shop.price());
        return OptionalInt.of(Math.min(carried, Math.min(room, ownerCanPay)));
    }
}
