package savage.commoneconomy.shopv2;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.storage.ShopStorage;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds and removes shops in memory and on disk together, in the order D7 sets so that a crash between the two files
 * leaves at most an unused item file, never a broken shop. If saving fails, the change is undone in memory as well.
 * Call it from the server thread only.
 */
public final class ShopChanges {

    private final ShopRegistry shops;
    private final ShopStorage storage;
    private final HolderLookup.Provider registries;

    public ShopChanges(ShopRegistry shops, ShopStorage storage, HolderLookup.Provider registries) {
        this.shops = shops;
        this.storage = storage;
        this.registries = registries;
    }

    /**
     * Writes the item file first, then adds the shop and saves shops.json. The caller has already checked that no
     * shop uses the same container.
     *
     * @param item the item to sell; a single-item copy is stored (D7)
     * @throws IOException if anything could not be saved; nothing is then left behind
     */
    public void create(Shop shop, ItemStack item) throws IOException {
        ItemStack template = item.copyWithCount(1);
        storage.writeItem(shop.id(), template, registries);
        if (!shops.add(shop, template)) {
            deleteItemFile(shop.id());
            throw new IllegalStateException("Another shop already has the container at " + shop.anchor());
        }
        try {
            storage.save(shops.all());
        } catch (IOException e) {
            shops.remove(shop);
            deleteItemFile(shop.id());
            throw e;
        }
    }

    /**
     * Saves shops.json first, then deletes the item file.
     *
     * @throws IOException if shops.json could not be saved; the shop is then still there
     */
    public void remove(Shop shop) throws IOException {
        ItemStack item = shops.item(shop.id());
        shops.remove(shop);
        try {
            storage.save(shops.all());
        } catch (IOException e) {
            shops.add(shop, item);
            throw e;
        }
        deleteItemFile(shop.id());
    }

    /**
     * Replaces a shop's record with a changed copy (a new sign, a new type, and so on) and saves shops.json.
     * The item file never changes (D7).
     *
     * @throws IOException if shops.json could not be saved; the old record is then back in memory
     */
    public void update(Shop updated) throws IOException {
        Shop previous = shops.get(updated.anchor());
        shops.replace(updated);
        try {
            storage.save(shops.all());
        } catch (IOException e) {
            shops.replace(previous);
            throw e;
        }
    }

    /**
     * A leftover item file is harmless and is reported when the server next starts (D7), so a failure is only logged.
     */
    private void deleteItemFile(UUID shopId) {
        try {
            storage.deleteItem(shopId);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.warn("Shop v2: could not delete the item file of shop {}; it is now unused.", shopId, e);
        }
    }
}
