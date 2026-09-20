package savage.commoneconomy.shopv2;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.config.ShopVersion;
import savage.commoneconomy.core.data.DataFolder;
import savage.commoneconomy.core.feature.Feature;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.storage.ShopStorage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Chest shops, version 2. Runs instead of the v1 shop (shop package) when shopVersion is "v2".
 * Under development: it loads shops and registers /shop info and /shop list so far, and no events yet.
 * The design and plan are in DESIGN.md next to this file.
 */
public class ShopV2Feature implements Feature {

    private ContainerRegistry containers;
    private ShopRegistry shops;
    private ShopHealth health;

    @Override
    public String id() {
        return "shops-v2";
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig().enableChestShops && ConfigManager.getShopVersion() == ShopVersion.V2;
    }

    @Override
    public void onInitialize() {
        SavsCommonEconomy.LOGGER.warn("Shop v2 is selected in config.json but is still under development and provides no shops yet. "
                + "Set \"shopVersion\" back to \"v1\" to use chest shops. Its data folder is {}", DataFolder.get());
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        new ShopCommands(this).register(dispatcher);
    }

    // Built when the server starts, so only call these from code that runs after that (commands and events)
    ContainerRegistry containers() {
        return containers;
    }

    ShopRegistry shops() {
        return shops;
    }

    ShopHealth health() {
        return health;
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        containers = new ContainerRegistry(ConfigManager.getConfig().shopAllowedContainers);
        shops = new ShopRegistry();
        health = new ShopHealth(containers, shops);

        Path v1File = FabricLoader.getInstance().getConfigDir().resolve(SavsCommonEconomy.MOD_ID).resolve("shops.json");
        try {
            ShopStorage.Loaded loaded = new ShopStorage(DataFolder.get()).load(v1File, server.registryAccess());
            loaded.problems().forEach(SavsCommonEconomy.LOGGER::warn);
            if (loaded.imported().imported() > 0 || loaded.imported().skipped() > 0) {
                SavsCommonEconomy.LOGGER.info("Shop v2: imported {} shop(s) from v1, left out {}.", loaded.imported().imported(), loaded.imported().skipped());
            }
            for (Shop shop : loaded.shops()) {
                if (!shops.add(shop, loaded.items().get(shop.id()))) {
                    SavsCommonEconomy.LOGGER.warn("Shop v2: shop {} has the same container as another shop at {} in {} and was left out of memory.",
                            shop.id(), shop.anchor().position(), shop.anchor().dimension());
                }
            }
            SavsCommonEconomy.LOGGER.info("Shop v2: loaded {} shop(s).", shops.size());
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Shop v2: could not load the shops.", e);
        }
    }
}
