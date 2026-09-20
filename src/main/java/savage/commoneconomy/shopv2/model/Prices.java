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

    /**
     * How many items a balance pays for at a unit price, for "all" (D9). A free shop has no limit. The result is
     * capped at what an int holds, so a huge balance cannot wrap around.
     */
    public static int affordable(BigDecimal balance, BigDecimal unitPrice) {
        if (unitPrice.signum() == 0) {
            return Integer.MAX_VALUE;
        }
        if (balance.signum() <= 0) {
            return 0;
        }
        return balance.divideToIntegralValue(unitPrice).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    public static boolean isValid(BigDecimal price) {
        return price != null && price.signum() >= 0 && !isTooHigh(price) && !hasTooManyDecimals(price);
    }
}
