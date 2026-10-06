package savage.commoneconomy.shop.model;

/**
 * The four kinds of trade (D6). The order of a trade is always the same: the payer pays, the goods move, the payee
 * is paid. The kind says who the payer and the payee are.
 */
public enum TradeKind {
    /** The player buys from a shop that a player owns: the buyer pays, the owner is paid. */
    BUY_FROM_PLAYER_SHOP,
    /** The player sells to a shop that a player owns: the owner pays, the seller is paid. */
    SELL_TO_PLAYER_SHOP,
    /** The player buys from an admin shop: the buyer pays, nobody is paid. */
    BUY_FROM_ADMIN_SHOP,
    /** The player sells to an admin shop: nobody pays, the seller is paid. */
    SELL_TO_ADMIN_SHOP;

    /** Who takes part in the money side of a trade. */
    public enum Party {
        /** The player who clicked the sign. */
        PLAYER,
        /** The shop's owner. */
        OWNER,
        /** Nobody: an admin shop has no owner to pay or to charge. */
        NONE
    }

    /**
     * @param mode the shop's mode: SELL means the shop sells to players, BUY means it buys from them
     */
    public static TradeKind of(ShopType type, ShopMode mode) {
        boolean admin = type == ShopType.ADMIN;
        if (mode == ShopMode.SELL) {
            return admin ? BUY_FROM_ADMIN_SHOP : BUY_FROM_PLAYER_SHOP;
        }
        return admin ? SELL_TO_ADMIN_SHOP : SELL_TO_PLAYER_SHOP;
    }

    public boolean playerBuys() {
        return this == BUY_FROM_PLAYER_SHOP || this == BUY_FROM_ADMIN_SHOP;
    }

    public boolean isAdminShop() {
        return this == BUY_FROM_ADMIN_SHOP || this == SELL_TO_ADMIN_SHOP;
    }

    public Party payer() {
        return switch (this) {
            case BUY_FROM_PLAYER_SHOP, BUY_FROM_ADMIN_SHOP -> Party.PLAYER;
            case SELL_TO_PLAYER_SHOP -> Party.OWNER;
            case SELL_TO_ADMIN_SHOP -> Party.NONE;
        };
    }

    public Party payee() {
        return switch (this) {
            case BUY_FROM_PLAYER_SHOP -> Party.OWNER;
            case SELL_TO_PLAYER_SHOP, SELL_TO_ADMIN_SHOP -> Party.PLAYER;
            case BUY_FROM_ADMIN_SHOP -> Party.NONE;
        };
    }
}
