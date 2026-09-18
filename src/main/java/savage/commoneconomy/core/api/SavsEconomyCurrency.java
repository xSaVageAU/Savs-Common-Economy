package savage.commoneconomy.core.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import savage.commoneconomy.core.EconomyManager;
import java.math.BigDecimal;
import java.math.BigInteger;

public class SavsEconomyCurrency implements EconomyCurrency {
    private final EconomyProvider provider;

    public SavsEconomyCurrency(EconomyProvider provider) {
        this.provider = provider;
    }

    @Override
    public Component name() {
        return Component.literal("Dollar");
    }

    @Override
    public Identifier id() {
        return Identifier.fromNamespaceAndPath("savs_common_economy", "dollar");
    }

    @Override
    public String formatValue(BigInteger value, boolean full) {
        // Divide the raw BigInteger by 100 before formatting to show the decimal point.
        return EconomyManager.getInstance().format(new BigDecimal(value).divide(new BigDecimal("100")));
    }

    @Override
    public Component formatValueComponent(BigInteger value, boolean full) {
        return Component.literal(formatValue(value, full));
    }

    @Override
    public BigInteger parseValue(String value) {
        try {
            if (value == null || value.isEmpty()) return BigInteger.ZERO;
            // Strip any currency symbols or characters that might be in the input string (e.g. "$", ",")
            String sanitizedValue = value.replaceAll("[^0-9.\\-]", "");
            if (sanitizedValue.isEmpty() || sanitizedValue.equals("-") || sanitizedValue.equals(".")) return BigInteger.ZERO;
            // Multiply the user input by 100 to convert dollars to raw subunits (cents).
            return new BigDecimal(sanitizedValue).multiply(new BigDecimal("100")).toBigInteger();
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    @Override
    public EconomyProvider provider() {
        return provider;
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.EMERALD);
    }
}
