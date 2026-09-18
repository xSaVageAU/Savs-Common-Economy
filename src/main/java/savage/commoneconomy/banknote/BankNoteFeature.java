package savage.commoneconomy.banknote;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.feature.Feature;

public class BankNoteFeature implements Feature {

    @Override
    public String id() {
        return "banknotes";
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig().enableBankNotes;
    }

    @Override
    public void onInitialize() {
        BankNoteListener.register();
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        BankNoteCommands.register(dispatcher);
    }
}
