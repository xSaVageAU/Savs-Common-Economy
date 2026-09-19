package savage.commoneconomy.core.data;

import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.File;
import java.nio.file.Path;

/**
 * Where the mod keeps data files, so they do not sit next to the config files:
 * config/savs-common-economy/data (next to the lang folder).
 */
public final class DataFolder {

    private DataFolder() {}

    /**
     * @return The data folder, created if it does not exist yet.
     */
    public static Path get() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SavsCommonEconomy.MOD_ID).resolve("data");
        File dirFile = dir.toFile();
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            SavsCommonEconomy.LOGGER.error("Could not create the data folder {}", dir);
        }
        return dir;
    }
}
