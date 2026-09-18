package savage.commoneconomy.sell;

import savage.commoneconomy.core.config.ConfigManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

/**
 * Item price lookups for /sell, /buy and /worth, backed by worth.json.
 * Items with no configured price return zero.
 */
public final class ItemWorth {

    private ItemWorth() {}

    public static BigDecimal getSellPrice(String itemId) {
        return ConfigManager.getWorth().sellPrices.getOrDefault(itemId, BigDecimal.ZERO);
    }

    public static BigDecimal getBuyPrice(String itemId) {
        return ConfigManager.getWorth().buyPrices.getOrDefault(itemId, BigDecimal.ZERO);
    }

    public static Map<String, BigDecimal> getAllSellPrices() {
        return Collections.unmodifiableMap(ConfigManager.getWorth().sellPrices);
    }

    public static Map<String, BigDecimal> getAllBuyPrices() {
        return Collections.unmodifiableMap(ConfigManager.getWorth().buyPrices);
    }
}
