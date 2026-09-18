package savage.commoneconomy.sell;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.feature.Feature;

public class SellFeature implements Feature {

    @Override
    public String id() {
        return "sell";
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig().enableSellCommands;
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        SellCommands.register(dispatcher);
    }
}
