package savage.commoneconomy.shopv2.storage;

import net.minecraft.core.HolderLookup;
import savage.commoneconomy.shopv2.model.Shop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads the shops at server start (D7): shops.json in the data folder plus the item files next to it.
 * The first time v2 runs, when shops.json does not exist yet, v1's shops are imported first.
 * It does no logging; the caller reports the problems. Call it from the server thread only.
 */
public final class ShopStorage {

    /**
     * @param imported what the v1 import did (all zero if it did not run)
     * @param problems plain-English lines for the caller to log
     */
    public record Loaded(List<Shop> shops, ShopImporter.Report imported, List<String> problems) {
    }

    private final ShopsFile shopsFile;
    private final ShopItemStore itemStore;

    public ShopStorage(Path dataFolder) {
        this.shopsFile = new ShopsFile(dataFolder.resolve("shops.json"));
        this.itemStore = new ShopItemStore(dataFolder.resolve("shopitems"));
    }

    public Loaded load(Path v1File, HolderLookup.Provider registries) throws IOException {
        List<String> problems = new ArrayList<>();

        ShopImporter.Report imported = new ShopImporter.Report(0, 0, List.of());
        if (!shopsFile.exists()) {
            imported = ShopImporter.run(v1File, shopsFile, itemStore, registries);
            problems.addAll(imported.problems());
        }

        ShopsFile.Loaded loaded = shopsFile.load();
        problems.addAll(loaded.problems());

        // When nothing could be loaded every item file would look unused, which would only mislead
        if (loaded.status() != ShopsFile.Status.CORRUPT && loaded.status() != ShopsFile.Status.NEWER_FORMAT) {
            List<UUID> ids = loaded.shops().stream().map(Shop::id).toList();
            for (String name : itemStore.findUnused(ids)) {
                problems.add("The item file " + name + " is not used by any loaded shop. It was left in place.");
            }
        }
        return new Loaded(loaded.shops(), imported, problems);
    }
}
