package savage.commoneconomy.config;

import java.math.BigDecimal;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(1000);
    public String currencySymbol = "$";
    public boolean symbolBeforeAmount = true;
    public boolean enableSellCommands = false;
}
