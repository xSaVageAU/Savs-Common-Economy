package savage.commoneconomy.shop;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.config.ShopVersion;
import savage.commoneconomy.core.feature.Feature;

public class ShopFeature implements Feature {

    @Override
    public String id() {
        return "shops";
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig().enableChestShops && ConfigManager.getShopVersion() == ShopVersion.V1;
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        ShopCommands.register(dispatcher);
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        ShopManager.getInstance().setServer(server);
        ShopManager.getInstance().load();
        ShopInteractionManager.getInstance().register();
    }
}
