package savage.commoneconomy.integration;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import eu.pb4.common.economy.api.EconomyTransaction;
import savage.commoneconomy.EconomyManager;
import java.math.BigDecimal;
import java.math.BigInteger;

public class SavsEconomyAccount implements EconomyAccount {
    private final GameProfile profile;
    private final EconomyCurrency currency;
    private final EconomyProvider provider;

    public SavsEconomyAccount(GameProfile profile, EconomyCurrency currency, EconomyProvider provider) {
        this.profile = profile;
        this.currency = currency;
        this.provider = provider;
    }

    @Override
    public Component name() {
        return Component.literal(profile.name());
    }

    @Override
    public UUID owner() {
        return profile.id();
    }

    @Override
    public Identifier id() {
        return Identifier.fromNamespaceAndPath("savs_common_economy", profile.id().toString());
    }

    @Override
    public EconomyCurrency currency() {
        return currency;
    }

    @Override
    public EconomyProvider provider() {
        return provider;
    }

    @Override
    public BigInteger balance() {
        // WARNING: Blocking call for API compatibility. 
        // We use join() here, but in practice the balance should be in our Caffeine cache.
        // Scale the balance up by 100 so the API sees cents as raw whole units.
        return EconomyManager.getInstance().getBalance(profile.id()).join()
            .multiply(new BigDecimal("100")).toBigInteger();
    }

    @Override
    public void setBalance(BigInteger value) {
        // Divide by 100 when receiving values from the API to translate raw units back into dollars.
        EconomyManager.getInstance().setBalance(profile.id(), new BigDecimal(value).divide(new BigDecimal("100")));
        if (currency instanceof SavsEconomyCurrency ecoCurrency) {
            sendFeedback(savage.commoneconomy.util.TranslationHelper.translate("api.economy.balance_set", ecoCurrency.formatValue(value, true)));
        }
    }

    @Override
    public EconomyTransaction increaseBalance(BigInteger value) {
        BigInteger current = balance();
        BigInteger next = current.add(value);
        setBalance(next);
        if (currency instanceof SavsEconomyCurrency ecoCurrency) {
            sendFeedback(savage.commoneconomy.util.TranslationHelper.translate("api.economy.balance_add", ecoCurrency.formatValue(value, true)));
        }
        return new EconomyTransaction.Simple(true, Component.literal("Success"), next, current, value, this);
    }

    @Override
    public EconomyTransaction decreaseBalance(BigInteger value) {
        BigInteger current = balance();
        if (current.compareTo(value) >= 0) {
            BigInteger next = current.subtract(value);
            setBalance(next);
            if (currency instanceof SavsEconomyCurrency ecoCurrency) {
                sendFeedback(savage.commoneconomy.util.TranslationHelper.translate("api.economy.balance_subtract", ecoCurrency.formatValue(value, true)));
            }
            return new EconomyTransaction.Simple(true, Component.literal("Success"), next, current, value.negate(), this);
        } else {
            return new EconomyTransaction.Simple(false, Component.literal("Insufficient funds"), current, current, value.negate(), this);
        }
    }

    private void sendFeedback(Component message) {
        net.minecraft.server.MinecraftServer server = savage.commoneconomy.SavsCommonEconomy.getServer();
        if (server != null) {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(profile.id());
            if (player != null) {
                var config = savage.commoneconomy.config.ConfigManager.getConfig();
                boolean overlay = (config.apiNotificationMode == savage.commoneconomy.config.EconomyConfig.NotificationMode.ACTION_BAR);
                server.execute(() -> {
                    player.sendSystemMessage(message, overlay);
                });
            }
        }
    }

    @Override
    public EconomyTransaction canDecreaseBalance(BigInteger value) {
        BigInteger current = balance();
        if (current.compareTo(value) >= 0) {
            return new EconomyTransaction.Simple(true, Component.literal("Success"), current.subtract(value), current, value.negate(), this);
        } else {
            return new EconomyTransaction.Simple(false, Component.literal("Insufficient funds"), current, current, value.negate(), this);
        }
    }

    @Override
    public EconomyTransaction canIncreaseBalance(BigInteger value) {
        BigInteger current = balance();
        return new EconomyTransaction.Simple(true, Component.literal("Success"), current.add(value), current, value, this);
    }
}
