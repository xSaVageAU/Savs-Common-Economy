package savage.commoneconomy.shop;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.data.DataFolder;
import savage.commoneconomy.core.feature.Feature;
import savage.commoneconomy.shop.model.Shop;
import savage.commoneconomy.shop.storage.ShopStorage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Chest shops. They run when enableChestShops is on, and replaced the original chest shops: those are imported once
 * from the old shops.json (see ShopImporter). The design is in DESIGN.md next to this file.
 */
public class ShopV2Feature implements Feature {

    private final RemoveMode removeMode = new RemoveMode();
    private final PendingTrades pendingTrades = new PendingTrades();
    private final TradeService trades = new TradeService(this);
    private final ShopChecker checker = new ShopChecker(this);
    private ContainerRegistry containers;
    private ShopRegistry shops;
    private ShopHealth health;
    private ShopChanges changes;
    private ShopSigns signs;

    @Override
    public String id() {
        return "shops-v2";
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig().enableChestShops;
    }

    @Override
    public void onInitialize() {
        ContainerChanges.activate();
        checker.register();
        new OwnerNames(this).register();
        new ShopProtection(this).register(); // before the click handler, so a refused click never reaches it
        new ShopClickHandler(this).register();
        new ShopChatHandler(this).register();
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

    ShopSigns signs() {
        return signs;
    }

    ShopChecker checker() {
        return checker;
    }

    TradeService trades() {
        return trades;
    }

    PendingTrades pendingTrades() {
        return pendingTrades;
    }

    RemoveMode removeMode() {
        return removeMode;
    }

    ShopChanges changes() {
        return changes;
    }

    ShopHealth health() {
        return health;
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        containers = new ContainerRegistry(ConfigManager.getConfig().shopAllowedContainers);
        shops = new ShopRegistry();
        ChestMergeRule.attach(shops);
        health = new ShopHealth(containers, shops);
        signs = new ShopSigns(containers);
        ShopStorage storage = new ShopStorage(DataFolder.get());
        changes = new ShopChanges(shops, storage, server.registryAccess());

        Path v1File = FabricLoader.getInstance().getConfigDir().resolve(SavsCommonEconomy.MOD_ID).resolve("shops.json");
        try {
            ShopStorage.Loaded loaded = storage.load(v1File, server.registryAccess());
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
