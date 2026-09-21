package savage.commoneconomy.shopv2.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The decisions of a trade as pure logic on plain values (D6): whether it can go ahead, how many items and what total,
 * and what to do when a later step fails. Reading the inventories and moving the goods happen elsewhere.
 */
public final class TradePlan {

    private TradePlan() {}

    /**
     * What was found when the trade was checked.
     *
     * @param playerHas     how many of the item the player carries
     * @param playerRoom    how many more of the item fit in the player's inventory
     * @param shopStock     how many of the item the shop's container holds (player shops only)
     * @param shopRoom      how many more of the item the shop's container takes (player shops only)
     * @param playerBalance the player's balance, or empty if it is not known
     */
    public record Facts(int playerHas, int playerRoom, int shopStock, int shopRoom, Optional<BigDecimal> playerBalance) {
    }

    public enum Refusal {
        OUT_OF_STOCK,
        NO_SPACE,
        INSUFFICIENT_FUNDS,
        NO_ITEMS,
        SHOP_NO_SPACE,
        /** A sale of "all" came to zero with items carried and room in the shop, so only the owner's funds can be the limit (D9). */
        OWNER_OUT_OF_FUNDS
    }

    /**
     * @param amount  how many items the trade moves, when it goes ahead
     * @param total   the one figure to charge, credit, log and show (D6); for INSUFFICIENT_FUNDS what was needed
     * @param refusal why it does not go ahead, or null if it does
     */
    public record Outcome(int amount, BigDecimal total, Refusal refusal) {

        public boolean goesAhead() {
            return refusal == null;
        }

        static Outcome go(int amount, BigDecimal total) {
            return new Outcome(amount, total, null);
        }

        static Outcome refused(Refusal refusal) {
            return new Outcome(0, null, refusal);
        }
    }

    /** The steps of a trade after the checks, in order (D6). */
    public enum Step {
        PAYER_CHARGE,
        GOODS_MOVE,
        PAYEE_CREDIT
    }

    /** What has to be done when a step fails. */
    public enum Recovery {
        /** Nothing else has happened yet, or there is nobody to repair. */
        NOTHING,
        /** Give the payer's money back. */
        REFUND_PAYER,
        /** The goods have moved but the payee was not paid: log who is owed and tell them. */
        PAYEE_IS_OWED
    }

    /**
     * The one total for a trade: price times amount, rounded to two decimals, half up (D6). Prices have at most two
     * decimals, so this only matters as a guard.
     */
    public static BigDecimal total(BigDecimal unitPrice, int amount) {
        return unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Checks whether the trade can go ahead. A purchase that asks for more than the shop has is refused, but a sale
     * of more than the player carries sells what they carry (kept from v1, D9). The player's balance is only checked
     * when the player pays and it is known; otherwise, and for an owner, the real charge decides.
     *
     * @param requested the amount asked for; zero or less can come from "all" finding nothing to trade, and the
     *                  refusal then names the limit it hit
     */
    public static Outcome plan(TradeKind kind, BigDecimal unitPrice, int requested, Facts facts) {
        return kind.playerBuys() ? planPurchase(kind, unitPrice, requested, facts) : planSale(kind, unitPrice, requested, facts);
    }

    private static Outcome planPurchase(TradeKind kind, BigDecimal unitPrice, int requested, Facts facts) {
        boolean admin = kind.isAdminShop();
        if (requested <= 0) {
            if (!admin && facts.shopStock() < 1) {
                return Outcome.refused(Refusal.OUT_OF_STOCK);
            }
            return Outcome.refused(facts.playerRoom() == 0 ? Refusal.NO_SPACE : Refusal.INSUFFICIENT_FUNDS);
        }
        if (!admin && facts.shopStock() < requested) {
            return Outcome.refused(Refusal.OUT_OF_STOCK);
        }
        if (facts.playerRoom() < requested) {
            return Outcome.refused(Refusal.NO_SPACE);
        }
        BigDecimal total = total(unitPrice, requested);
        // An unknown balance is not refused here: the real charge decides (D6)
        if (facts.playerBalance().isPresent() && facts.playerBalance().get().compareTo(total) < 0) {
            return new Outcome(0, total, Refusal.INSUFFICIENT_FUNDS);
        }
        return Outcome.go(requested, total);
    }

    private static Outcome planSale(TradeKind kind, BigDecimal unitPrice, int requested, Facts facts) {
        if (requested <= 0) {
            // "all" found nothing to sell. Like a purchase, the refusal names the limit it hit: what the player carries,
            // then the shop's room, and what is left is the owner's funds (D9)
            if (facts.playerHas() < 1) {
                return Outcome.refused(Refusal.NO_ITEMS);
            }
            if (!kind.isAdminShop() && facts.shopRoom() < 1) {
                return Outcome.refused(Refusal.SHOP_NO_SPACE);
            }
            return Outcome.refused(Refusal.OWNER_OUT_OF_FUNDS);
        }
        int amount = Math.min(requested, facts.playerHas());
        if (amount <= 0) {
            return Outcome.refused(Refusal.NO_ITEMS);
        }
        if (!kind.isAdminShop() && facts.shopRoom() < amount) {
            return Outcome.refused(Refusal.SHOP_NO_SPACE);
        }
        return Outcome.go(amount, total(unitPrice, amount));
    }

    /**
     * What to do when a step fails (D6): if the payer could not pay, nothing else has happened; if the goods could
     * not move, the payer gets their money back; if the payee could not be paid, they are owed. A step nobody takes
     * part in cannot fail, and needs nothing.
     */
    public static Recovery recoveryFor(TradeKind kind, Step failed) {
        return switch (failed) {
            case PAYER_CHARGE -> Recovery.NOTHING;
            case GOODS_MOVE -> kind.payer() == TradeKind.Party.NONE ? Recovery.NOTHING : Recovery.REFUND_PAYER;
            case PAYEE_CREDIT -> kind.payee() == TradeKind.Party.NONE ? Recovery.NOTHING : Recovery.PAYEE_IS_OWED;
        };
    }
}
