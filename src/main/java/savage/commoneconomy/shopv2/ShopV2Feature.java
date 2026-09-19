package savage.commoneconomy.shopv2;

import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.config.ShopVersion;
import savage.commoneconomy.core.data.DataFolder;
import savage.commoneconomy.core.feature.Feature;

/**
 * Chest shops, version 2. Runs instead of the v1 shop (shop package) when shopVersion is "v2".
 * Scaffold only for now: it registers nothing. The design and plan are in DESIGN.md next to this file.
 */
public class ShopV2Feature implements Feature {

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
}
