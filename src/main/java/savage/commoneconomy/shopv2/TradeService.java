package savage.commoneconomy.shopv2;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.inventory.InventorySpace;
import savage.commoneconomy.core.log.TransactionLogger;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopStatus;
import savage.commoneconomy.shopv2.model.TradeKind;
import savage.commoneconomy.shopv2.model.TradePlan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Runs a trade (D6). The order is always the same: the payer pays, the goods move, the payee is paid.
 * <ol>
 *   <li>On the main thread the shop is locked and the trade is checked and planned ({@link TradePlan}).
 *   <li>The payer is charged, which runs on the economy's threads, then the result comes back to the main thread.
 *   <li>On the main thread the shop and the player are checked once more and the goods move all-or-nothing.
 *       If that fails the payer's money is given back.
 *   <li>The payee is credited. If that fails they are owed: it is logged and they are told.
 * </ol>
 * Items are never held in memory while waiting for the economy. Call {@link #start} from the server thread only.
 */
final class TradeService {

    private final ShopV2Feature feature;
    private final TradeLocks locks = new TradeLocks();

    TradeService(ShopV2Feature feature) {
        this.feature = feature;
    }

    /**
     * One trade in progress.
     *
     * @param shop   the shop as it was when the trade was planned; a later change to it aborts the trade
     * @param item   the shop's item template
     * @param amount how many items move
     * @param total  the one figure to charge, credit and show
     * @param token  identifies this trade's hold on the shop
     */
    private record Trade(ServerPlayer player, Shop shop, ItemStack item, TradeKind kind, int amount, BigDecimal total, long token) {

        MinecraftServer server() {
            return player.level().getServer();
        }
    }

    /**
     * Starts a trade for a shop that has just been checked (it exists, its status is OK, and the player is in range).
     *
     * @param requested how many items the player asked for; zero or less can come from "all" finding nothing
     */
    void start(ServerPlayer player, Shop shop, int requested) {
        TradeLocks.Attempt attempt = locks.tryAcquire(shop.anchor(), System.currentTimeMillis());
        if (!attempt.acquired()) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.busy"));
            return;
        }
        if (attempt.stuckLockFreed()) {
            SavsCommonEconomy.LOGGER.error("Shop v2: a trade at {} in {} was holding the shop for over {} seconds and was freed. "
                    + "A payment probably never completed.", shop.anchor().position(), shop.anchor().dimension(), TradeLocks.WATCHDOG_MILLIS / 1000);
        }

        try {
            check(player, shop, requested, attempt.token());
        } catch (RuntimeException e) {
            failed(player, shop, attempt.token(), e);
        }
    }

    /**
     * Step 1: gathers what the trade needs, plans it, and either refuses it or goes on to the payment.
     */
    private void check(ServerPlayer player, Shop shop, int requested, long token) {
        ServerLevel level = player.level();
        ItemStack item = feature.shops().item(shop.id());
        TradeKind kind = TradeKind.of(shop.type(), shop.mode());
        Container container = kind.isAdminShop() ? null : feature.containers().resolve(level, Positions.toBlockPos(shop.anchor().position()));

        TradePlan.Facts facts = new TradePlan.Facts(
                PlayerItems.count(player, item),
                InventorySpace.getAvailableSpace(player, item),
                container == null ? 0 : ContainerStock.count(container, item),
                container == null ? 0 : ContainerStock.space(container, item),
                EconomyService.get().getCachedBalance(player.getUUID()));
        TradePlan.Outcome outcome = TradePlan.plan(kind, shop.price(), requested, facts);
        if (!outcome.goesAhead()) {
            locks.release(shop.anchor(), token);
            player.sendSystemMessage(refusalMessage(outcome));
            return;
        }

        Trade trade = new Trade(player, shop, item, kind, outcome.amount(), outcome.total(), token);
        if (kind.payer() == TradeKind.Party.NONE) {
            moveGoods(trade);
        } else {
            charge(trade);
        }
    }

    private Component refusalMessage(TradePlan.Outcome outcome) {
        return switch (outcome.refusal()) {
            case OUT_OF_STOCK -> TranslationHelper.translate("shop.transaction.out_of_stock");
            case NO_SPACE -> TranslationHelper.translate("shop.transaction.no_space");
            case INSUFFICIENT_FUNDS -> outcome.total() == null
                    ? TranslationHelper.translate("shop.transaction.insufficient_funds")
                    : TranslationHelper.translate("shop.transaction.insufficient_funds_detail", EconomyService.get().format(outcome.total()));
            case NO_ITEMS -> TranslationHelper.translate("shop.transaction.no_items");
            case SHOP_NO_SPACE -> TranslationHelper.translate("shop.transaction.shop_no_space");
            case OWNER_OUT_OF_FUNDS -> TranslationHelper.translate("shop.transaction.owner_out_of_funds");
        };
    }

    /**
     * Step 2: the payer pays. The result arrives on the economy's threads, so it hops back to the main thread.
     */
    private void charge(Trade trade) {
        EconomyService.get().removeBalance(payerId(trade), trade.total()).whenComplete((charged, error) -> trade.server().execute(() -> {
            try {
                if (error != null) {
                    SavsCommonEconomy.LOGGER.error("Shop v2: charging {} for a trade at {} in {} ended in an error, so the trade was cancelled. "
                            + "Check whether they were charged.", payerId(trade), trade.shop().anchor().position(), trade.shop().anchor().dimension(), error);
                }
                if (error != null || !Boolean.TRUE.equals(charged)) {
                    locks.release(trade.shop().anchor(), trade.token());
                    // Nothing else has happened yet (D6)
                    trade.player().sendSystemMessage(trade.kind().payer() == TradeKind.Party.PLAYER
                            ? TranslationHelper.translate("shop.transaction.insufficient_funds_detail", EconomyService.get().format(trade.total()))
                            : TranslationHelper.translate("shop.transaction.owner_out_of_funds"));
                    return;
                }
                moveGoods(trade);
            } catch (RuntimeException e) {
                failed(trade.player(), trade.shop(), trade.token(), e);
            }
        }));
    }

    /**
     * Step 3: the goods move, all-or-nothing, on the main thread. Whatever happens the shop is released here,
     * because from now on nothing else touches the shop.
     */
    private void moveGoods(Trade trade) {
        boolean moved;
        try {
            moved = tryMoveGoods(trade);
        } catch (RuntimeException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: moving the goods of a trade at {} in {} failed with an error.",
                    trade.shop().anchor().position(), trade.shop().anchor().dimension(), e);
            moved = false;
        }
        locks.release(trade.shop().anchor(), trade.token());

        if (!moved) {
            if (!trade.player().hasDisconnected()) {
                trade.player().sendSystemMessage(TranslationHelper.translate(
                        trade.kind().playerBuys() ? "shop.transaction.item_transfer_error" : "shop.transaction.shop_inventory_error"));
            }
            if (TradePlan.recoveryFor(trade.kind(), TradePlan.Step.GOODS_MOVE) == TradePlan.Recovery.REFUND_PAYER) {
                refundPayer(trade);
            }
            return;
        }

        // The goods have moved, so nothing here may stop the payee from being paid
        try {
            trade.player().sendSystemMessage(successMessage(trade));
            feature.signs().refresh(trade.player().level(), trade.shop(), trade.item());
        } catch (RuntimeException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: a trade at {} in {} went through but telling the player or refreshing the sign failed.",
                    trade.shop().anchor().position(), trade.shop().anchor().dimension(), e);
        }
        creditPayee(trade);
    }

    /**
     * Checks once more that nothing changed while the payment was in flight, then moves the goods. The player
     * must still be here, because items given to a player who is gone are lost. The shop must still exist, be
     * exactly the shop that was planned, and be OK.
     *
     * @return false if the trade was abandoned with nothing moved
     */
    private boolean tryMoveGoods(Trade trade) {
        ServerPlayer player = trade.player();
        if (player.hasDisconnected() || !player.isAlive() || player.isRemoved()) {
            return false;
        }
        Shop shop = trade.shop();
        if (!shop.equals(feature.shops().get(shop.anchor()))
                || feature.health().statusOf(player.level().getServer(), shop) != ShopStatus.OK) {
            return false;
        }

        ItemStack item = trade.item();
        int amount = trade.amount();
        Container container = null;
        if (!trade.kind().isAdminShop()) {
            container = feature.containers().resolve(player.level(), Positions.toBlockPos(shop.anchor().position()));
            if (container == null) {
                return false;
            }
        }

        switch (trade.kind()) {
            case BUY_FROM_PLAYER_SHOP -> {
                if (!ContainerMoves.remove(container, item, amount)) {
                    return false;
                }
                PlayerItems.give(player, item, amount);
                return true;
            }
            case BUY_FROM_ADMIN_SHOP -> {
                PlayerItems.give(player, item, amount);
                return true;
            }
            case SELL_TO_PLAYER_SHOP -> {
                if (PlayerItems.count(player, item) < amount || !ContainerMoves.insert(container, item, amount)) {
                    return false;
                }
                if (!PlayerItems.remove(player, item, amount)) {
                    // Cannot happen on one thread after the count above, but never keep items that were not taken
                    ContainerMoves.remove(container, item, amount);
                    return false;
                }
                return true;
            }
            case SELL_TO_ADMIN_SHOP -> {
                return PlayerItems.remove(player, item, amount);
            }
        }
        return false;
    }

    private Component successMessage(Trade trade) {
        String key = switch (trade.kind()) {
            case BUY_FROM_PLAYER_SHOP, BUY_FROM_ADMIN_SHOP -> "shop.transaction.buy_success";
            case SELL_TO_PLAYER_SHOP -> "shop.transaction.sell_success";
            case SELL_TO_ADMIN_SHOP -> "shop.transaction.sell_admin_success";
        };
        return TranslationHelper.translate(key, trade.amount(), trade.item().getHoverName(), EconomyService.get().format(trade.total()));
    }

    /**
     * Step 4: the payee is paid last, because a refund needs money (D6). If that fails the goods have already
     * moved, so they are owed: it is logged and they are told if they are online.
     */
    private void creditPayee(Trade trade) {
        if (trade.kind().payee() == TradeKind.Party.NONE) {
            logCompleted(trade);
            return;
        }
        UUID payee = payeeId(trade);
        String payeeName = payeeName(trade);
        EconomyService.get().addBalance(payee, trade.total()).whenComplete((credited, error) -> {
            if (error == null && Boolean.TRUE.equals(credited)) {
                trade.server().execute(() -> logCompleted(trade));
                return;
            }
            trade.server().execute(() -> {
                SavsCommonEconomy.LOGGER.error("Shop v2: a trade by {} ({}) at {} in {} completed but crediting {} ({}) with {} failed; they are owed this amount.",
                        trade.player().getName().getString(), trade.player().getUUID(), trade.shop().anchor().position(),
                        trade.shop().anchor().dimension(), payeeName, payee, trade.total().toPlainString(), error);
                TransactionLogger.log("TRANSFER_FAILED", payerName(trade), payeeName, trade.total(),
                        "Shop payment failed so " + payeeName + " is owed this amount");
                tell(trade, payee, TranslationHelper.translate("shop.transaction.payment_not_credited", EconomyService.get().format(trade.total())));
            });
        });
    }

    /**
     * Logs a trade that went through completely (D11), following the convention /buy and /sell use: the source is
     * whoever provides the items, and the server for an admin shop. The reason names the item by its id and the shop
     * by its dimension and position, so an admin can find the shop. A trade that was refused, abandoned or left
     * someone owed is not logged here.
     */
    private void logCompleted(Trade trade) {
        Shop shop = trade.shop();
        String owner = trade.kind().isAdminShop() ? "Server" : shop.ownerName();
        String buyerOrSeller = trade.player().getName().getString();
        String where = shop.anchor().dimension() + " " + shop.anchor().position().x() + " " + shop.anchor().position().y() + " "
                + shop.anchor().position().z();
        String itemId = BuiltInRegistries.ITEM.getKey(trade.item().getItem()).toString();

        if (trade.kind().playerBuys()) {
            TransactionLogger.log("SHOP_BUY", owner, buyerOrSeller, trade.total(), "Bought " + trade.amount() + "x " + itemId + " at " + where);
        } else {
            TransactionLogger.log("SHOP_SELL", buyerOrSeller, owner, trade.total(), "Sold " + trade.amount() + "x " + itemId + " at " + where);
        }
    }

    /**
     * Gives the payer's money back after the goods could not move. If the refund fails too they are owed, which is
     * handled the same way as an unpaid payee.
     */
    private void refundPayer(Trade trade) {
        UUID payer = payerId(trade);
        String refundedName = nameOf(trade.kind().payer(), trade);
        EconomyService.get().addBalance(payer, trade.total()).whenComplete((refunded, error) -> {
            if (error == null && Boolean.TRUE.equals(refunded)) {
                return;
            }
            trade.server().execute(() -> {
                SavsCommonEconomy.LOGGER.error("Shop v2: a trade at {} in {} failed and refunding {} ({}) with {} also failed; they are owed this amount.",
                        trade.shop().anchor().position(), trade.shop().anchor().dimension(), refundedName, payer, trade.total().toPlainString(), error);
                TransactionLogger.log("TRANSFER_FAILED", "Shop", refundedName, trade.total(),
                        "Shop refund failed so " + refundedName + " is owed this amount");
                tell(trade, payer, TranslationHelper.translate("shop.transaction.refund_failed"));
            });
        });
    }

    /**
     * An unexpected error. The shop is released, so the lock never outlives the trade, and the player is told.
     */
    private void failed(ServerPlayer player, Shop shop, long token, RuntimeException error) {
        locks.release(shop.anchor(), token);
        SavsCommonEconomy.LOGGER.error("Shop v2: a trade at {} in {} ended in an error.", shop.anchor().position(), shop.anchor().dimension(), error);
        if (!player.hasDisconnected()) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.item_transfer_error"));
        }
    }

    private void tell(Trade trade, UUID who, Component message) {
        ServerPlayer online = trade.server().getPlayerList().getPlayer(who);
        if (online != null) {
            online.sendSystemMessage(message);
        }
    }

    private static UUID payerId(Trade trade) {
        return trade.kind().payer() == TradeKind.Party.OWNER ? trade.shop().owner() : trade.player().getUUID();
    }

    private static UUID payeeId(Trade trade) {
        return trade.kind().payee() == TradeKind.Party.OWNER ? trade.shop().owner() : trade.player().getUUID();
    }

    private static String payeeName(Trade trade) {
        return nameOf(trade.kind().payee(), trade);
    }

    private static String nameOf(TradeKind.Party party, Trade trade) {
        return party == TradeKind.Party.OWNER ? trade.shop().ownerName() : trade.player().getName().getString();
    }

    /**
     * The name to log as who was supposed to pay: the party that provides the money, or "Server" for an admin shop.
     */
    private static String payerName(Trade trade) {
        return trade.kind().payer() == TradeKind.Party.NONE ? "Server" : nameOf(trade.kind().payer(), trade);
    }
}
