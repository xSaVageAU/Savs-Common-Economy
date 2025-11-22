package savage.commoneconomy.integration;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import eu.pb4.common.economy.api.EconomyTransaction;
import savage.commoneconomy.EconomyManager;
import java.math.BigDecimal;

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
    public Text name() {
        return Text.of(profile.name());
    }

    @Override
    public UUID owner() {
        return profile.id();
    }

    @Override
    public Identifier id() {
        return Identifier.of("savs_common_economy", profile.id().toString());
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
    public void setBalance(long value) {
        EconomyManager.getInstance().setBalance(profile.id(), BigDecimal.valueOf(value));
    }

    @Override
    public EconomyTransaction canDecreaseBalance(long value) {
        long current = balance();
        if (current >= value) {
            return new EconomyTransaction.Simple(true, Text.of("Success"), current - value, value, current, this);
        } else {
            return new EconomyTransaction.Simple(false, Text.of("Insufficient funds"), current, value, current, this);
        }
    }

    @Override
    public EconomyTransaction decreaseBalance(long value) {
        long current = balance();
        if (EconomyManager.getInstance().removeBalance(profile.id(), BigDecimal.valueOf(value))) {
            sendFeedback("§e[Economy] §c-" + currency.formatValue(value, true));
            return new EconomyTransaction.Simple(true, Text.of("Success"), current - value, value, current, this);
        }
        return new EconomyTransaction.Simple(false, Text.of("Insufficient funds"), current, value, current, this);
    }

    @Override
    public EconomyTransaction increaseBalance(long value) {
        long current = balance();
        if (EconomyManager.getInstance().addBalance(profile.id(), BigDecimal.valueOf(value))) {
            sendFeedback("§e[Economy] §a+" + currency.formatValue(value, true));
            return new EconomyTransaction.Simple(true, Text.of("Success"), current + value, value, current, this);
        }
        return new EconomyTransaction.Simple(false, Text.of("Failed"), current, value, current, this);
    }

    private void sendFeedback(String message) {
        net.minecraft.server.MinecraftServer server = EconomyManager.getInstance().getServer();
        if (server != null) {
            net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(profile.id());
            if (player != null) {
                player.sendMessage(Text.literal(message), false);
            }
        }
    }

    @Override
    public EconomyTransaction canIncreaseBalance(long value) {
        long current = balance();
        return new EconomyTransaction.Simple(true, Text.of("Success"), current + value, value, current, this);
    }

    @Override
    public long balance() {
        return EconomyManager.getInstance().getBalance(profile.id()).longValue();
    }
}
