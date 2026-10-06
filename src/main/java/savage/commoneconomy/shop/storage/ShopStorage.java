package savage.commoneconomy.shop.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.shop.model.Shop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the shops at server start (D7): the shop files in the shops folder plus the item files next to it, and saves
 * a shop's file or item file right away. The first time v2 runs, when the shops folder does not exist yet, v1's shops
 * are imported first. It does no logging; the caller reports the problems. Call it from the server thread only.
 */
public final class ShopStorage {

    /**
     * @param items    each shop's item; a shop whose item file could not be used has none, and is still in {@code shops}
     * @param imported what the v1 import did (all zero if it did not run)
     * @param problems plain-English lines for the caller to log
     */
    public record Loaded(List<Shop> shops, Map<UUID, ItemStack> items, ShopImporter.Report imported, List<String> problems) {
    }

    private final ShopFolder shopFolder;
    private final ShopItemStore itemStore;

    public ShopStorage(Path dataFolder) {
        this.shopFolder = new ShopFolder(dataFolder.resolve("shops"));
        this.itemStore = new ShopItemStore(dataFolder.resolve("shopitems"));
    }

    public Loaded load(Path v1File, HolderLookup.Provider registries) throws IOException {
        List<String> problems = new ArrayList<>();

        ShopImporter.Report imported = new ShopImporter.Report(0, 0, List.of());
        if (!shopFolder.exists()) {
            imported = ShopImporter.run(v1File, shopFolder, itemStore, registries);
            problems.addAll(imported.problems());
        }

        ShopFolder.Loaded loaded = shopFolder.load();
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

        // The item of a shop whose file could not be read is still in use, so it is not reported as unused
        Set<UUID> referenced = new HashSet<>(loaded.unreadable());
        loaded.shops().forEach(shop -> referenced.add(shop.id()));
        for (String name : itemStore.findUnused(referenced)) {
            problems.add("The item file " + name + " is not used by any loaded shop. It was left in place.");
        }
        return new Loaded(loaded.shops(), items, imported, problems);
    }

    /**
     * Saves one shop's file right away (D7). Only that file is written, so a shop that was never loaded cannot be
     * overwritten.
     */
    public void saveShop(Shop shop) throws IOException {
        shopFolder.save(shop);
    }

    public void deleteShop(UUID shopId) throws IOException {
        shopFolder.delete(shopId);
    }

    public void writeItem(UUID shopId, ItemStack item, HolderLookup.Provider registries) throws IOException {
        itemStore.write(shopId, item, registries);
    }

    public void deleteItem(UUID shopId) throws IOException {
        itemStore.delete(shopId);
    }
}
