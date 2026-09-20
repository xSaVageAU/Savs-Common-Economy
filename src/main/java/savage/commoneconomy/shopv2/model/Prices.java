package savage.commoneconomy.shopv2.model;

import java.math.BigDecimal;

/**
 * The price rules from D10: from 0 up to a fixed maximum, with at most two decimals.
 * The maximum keeps a trade total inside what the database can store.
 */
public final class Prices {

    public static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000_000);

    private static final int MAX_DECIMALS = 2;

    private Prices() {}

    public static boolean hasTooManyDecimals(BigDecimal price) {
        return price.stripTrailingZeros().scale() > MAX_DECIMALS;
    }

    public static boolean isTooHigh(BigDecimal price) {
        return price.compareTo(MAX_PRICE) > 0;
    }

    public static boolean isValid(BigDecimal price) {
        return price != null && price.signum() >= 0 && !isTooHigh(price) && !hasTooManyDecimals(price);
    }
}
