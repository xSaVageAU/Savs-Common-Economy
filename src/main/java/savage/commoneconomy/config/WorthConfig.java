package savage.commoneconomy.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class WorthConfig {
    public Map<String, BigDecimal> itemPrices = new HashMap<>();

    public WorthConfig() {
        // Default example
        itemPrices.put("minecraft:apple", BigDecimal.valueOf(10.0));
    }
}
