package savage.commoneconomy.shopv2.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.shopv2.model.Shop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads the shops at server start (D7): shops.json in the data folder plus the item files next to it.
 * The first time v2 runs, when shops.json does not exist yet, v1's shops are imported first.
 * It does no logging; the caller reports the problems. Call it from the server thread only.
 */
public final class ShopStorage {

    /**
     * @param items    each shop's item; a shop whose item file could not be used has none, and is still in {@code shops}
     * @param imported what the v1 import did (all zero if it did not run)
     * @param problems plain-English lines for the caller to log
     */
    public record Loaded(List<Shop> shops, Map<UUID, ItemStack> items, ShopImporter.Report imported, List<String> problems) {
    }

    private final ShopsFile shopsFile;
    private final ShopItemStore itemStore;
    private boolean ready;

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

        Map<UUID, ItemStack> items = new HashMap<>();
        for (Shop shop : loaded.shops()) {
            ShopItemStore.Loaded item = itemStore.read(shop.id(), registries);
            if (item.item() != null) {
                items.put(shop.id(), item.item());
            }
            if (item.problem() != null) {
                problems.add("Shop " + shop.id() + ": " + item.problem());
            }
        }

        // When nothing could be loaded every item file would look unused, which would only mislead
        if (loaded.status() != ShopsFile.Status.CORRUPT && loaded.status() != ShopsFile.Status.NEWER_FORMAT) {
            List<UUID> ids = loaded.shops().stream().map(Shop::id).toList();
            for (String name : itemStore.findUnused(ids)) {
                problems.add("The item file " + name + " is not used by any loaded shop. It was left in place.");
            }
        }
        ready = true;
        return new Loaded(loaded.shops(), items, imported, problems);
    }

    /**
     * Saves shops.json right away (D7). Refused until the shops have been loaded completely, and for a file written
     * by a newer version, so a shop that was never read can never be overwritten.
     */
    public void save(Collection<Shop> shops) throws IOException {
        if (!ready) {
            throw new IOException("the shops were not loaded completely, so shops.json is not being changed");
        }
        if (!shopsFile.save(shops)) {
            throw new IOException("shops.json was written by a newer version and is not being changed");
        }
    }

    public void writeItem(UUID shopId, ItemStack item, HolderLookup.Provider registries) throws IOException {
        itemStore.write(shopId, item, registries);
    }

    public void deleteItem(UUID shopId) throws IOException {
        itemStore.delete(shopId);
    }
}
