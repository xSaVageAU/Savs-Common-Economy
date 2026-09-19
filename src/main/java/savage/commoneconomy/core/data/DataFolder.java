package savage.commoneconomy.core.data;

import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.File;
import java.nio.file.Path;

/**
 * Where the mod keeps data, as opposed to configuration: {server folder}/data/savs-common-economy.
 * Config stays under config/savs-common-economy.
 */
public final class DataFolder {

    private DataFolder() {}

    /**
     * @return The data folder, created if it does not exist yet.
     */
    public static Path get() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("data").resolve(SavsCommonEconomy.MOD_ID);
        File dirFile = dir.toFile();
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            SavsCommonEconomy.LOGGER.error("Could not create the data folder {}", dir);
        }
        return dir;
    }
}
