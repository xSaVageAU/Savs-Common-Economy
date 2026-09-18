package savage.commoneconomy.core.api;

import net.minecraft.network.chat.Component;
import eu.pb4.common.economy.api.EconomyProvider;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyAccount;
import net.minecraft.server.MinecraftServer;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Collection;

public class SavsEconomyProvider implements EconomyProvider {
    public static final SavsEconomyProvider INSTANCE = new SavsEconomyProvider();
    private final SavsEconomyCurrency currency = new SavsEconomyCurrency(this);

    private SavsEconomyProvider() {}

    @Override
    public Component name() {
        return Component.literal("Savs Common Economy");
    }

    @Override
    public ItemStack icon() {
        return Items.EMERALD.getDefaultInstance();
    }


    @Override
    public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
        return java.util.Collections.singletonList(currency);
    }

    @Override
    public EconomyCurrency getCurrency(MinecraftServer server, String id) {
        if (id.equalsIgnoreCase("dollar") || id.equalsIgnoreCase("savs_common_economy:dollar")) {
            return currency;
        }
        return null;
    }

    @Override
    public String defaultAccount(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
        if (currency == this.currency) {
            return profile.id().toString();
        }
        return null;
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
        return java.util.Collections.singletonList(new SavsEconomyAccount(profile, currency, this));
    }

    @Override
    public EconomyAccount getAccount(MinecraftServer server, GameProfile profile, String id) {
        if (id.equals(profile.id().toString())) {
            return new SavsEconomyAccount(profile, currency, this);
        }
        return null;
    }
}
