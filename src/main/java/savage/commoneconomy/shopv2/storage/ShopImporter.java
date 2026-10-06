package savage.commoneconomy.shopv2.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import savage.commoneconomy.shopv2.model.BlockLocation;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Prices;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopMode;
import savage.commoneconomy.shopv2.model.ShopType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one-time import of v1's shops.json into v2 (D7). v1's file is only read, and once the import is done it is
 * renamed to shops.json.old. A v1 shop that cannot be converted is left out and reported; it stays in that file.
 * The import never reads blocks (that could load chunks), so every imported shop has no sign yet
 * and is marked to have it looked up when its chunk is first checked (D8).
 */
public final class ShopImporter {

    /**
     * @param problems plain-English lines for the caller to log: shops left out, and prices that were rounded
     */
    public record Report(int imported, int skipped, List<String> problems) {
    }

    private ShopImporter() {}

    /**
     * Converts the shops in v1's file and writes them: the item files first, then the shop files (D7's order), which
     * appear together or not at all (see {@link ShopFolder#createAll}).
     * Does nothing if v1's file is missing or if v2 already has its shops folder, so it can never overwrite one.
     * If no shop could be imported, nothing is written, so a later start can try again. Once the shops are written,
     * v1's file is renamed to shops.json.old (see {@link #renameToOld}).
     */
    public static Report run(Path v1File, ShopFolder shopFolder, ShopItemStore itemStore, HolderLookup.Provider registries)
            throws IOException {
        List<String> problems = new ArrayList<>();

        if (shopFolder.exists()) {
            problems.add("v2 already has its shops folder, so nothing was imported.");
            return new Report(0, 0, problems);
        }
        if (!Files.exists(v1File)) {
            return new Report(0, 0, problems);
        }

        JsonElement entries;
        try {
            entries = JsonParser.parseString(new String(Files.readAllBytes(v1File), StandardCharsets.UTF_8)).getAsJsonObject().get("shops");
        } catch (JsonParseException | IllegalStateException e) {
            problems.add("v1's shops.json is not valid JSON, so nothing was imported.");
            return new Report(0, 0, problems);
        }
        if (entries == null || !entries.isJsonArray()) {
            problems.add("v1's shops.json has no list of shops, so nothing was imported.");
            return new Report(0, 0, problems);
        }

        List<Shop> shops = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int skipped = 0;
        int number = 0;
        for (JsonElement entry : entries.getAsJsonArray()) {
            number++;
            String label = "v1 shop #" + number;
            try {
                if (!entry.isJsonObject()) {
                    throw new IllegalArgumentException("the entry is not an object");
                }
                JsonObject json = entry.getAsJsonObject();
                label = "v1 shop " + text(json, "shopId");
                Shop shop = convert(json, label, problems);
                if (!seen.add(shop.id())) {
                    throw new IllegalArgumentException("another shop already has this id");
                }
                itemStore.write(shop.id(), readItem(json, registries), registries);
                shops.add(shop);
            } catch (RuntimeException | IOException e) {
                skipped++;
                problems.add(label + " was not imported: " + e.getMessage());
            }
        }

        if (!shops.isEmpty()) {
            shopFolder.createAll(shops);
            renameToOld(v1File, problems);
        }
        return new Report(shops.size(), skipped, problems);
    }

    /**
     * Renames v1's file to shops.json.old once the import is done, so the migration is visibly one and done (DESIGN
     * section 8). An existing shops.json.old is never overwritten. A rename that fails is only reported: the import has
     * already succeeded, and it will not run again because the shops folder now exists.
     */
    private static void renameToOld(Path v1File, List<String> problems) {
        Path old = v1File.resolveSibling(v1File.getFileName() + ".old");
        try {
            Files.move(v1File, old);
        } catch (FileAlreadyExistsException e) {
            problems.add("v1's " + v1File.getFileName() + " was imported but not renamed, because " + old.getFileName()
                    + " already exists. Both were left as they are.");
        } catch (IOException e) {
            problems.add("v1's " + v1File.getFileName() + " was imported but could not be renamed to " + old.getFileName()
                    + " (" + e.getMessage() + "). It was left as it is.");
        }
    }

    private static Shop convert(JsonObject json, String label, List<String> problems) {
        JsonObject chest = object(json, "chestLocation");
        return new Shop(
                UUID.fromString(text(json, "shopId")),
                new BlockLocation(text(json, "worldId"), new Position(whole(chest, "x"), whole(chest, "y"), whole(chest, "z"))),
                UUID.fromString(text(json, "ownerId")),
                text(json, "ownerName"),
                shopType(text(json, "type")),
                flag(json, "buying") ? ShopMode.BUY : ShopMode.SELL,
                price(text(json, "price"), label, problems),
                null,
                true);
    }

    private static ShopType shopType(String text) {
        try {
            return ShopType.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown shop type \"" + text + "\"");
        }
    }

    private static BigDecimal price(String text, String label, List<String> problems) {
        BigDecimal original = new BigDecimal(text);
        BigDecimal rounded = original.setScale(2, RoundingMode.HALF_UP);
        if (original.stripTrailingZeros().scale() > 2) {
            problems.add(label + ": the price " + original.toPlainString() + " was rounded to " + rounded.toPlainString() + ".");
            if (rounded.signum() == 0 && original.signum() != 0) {
                problems.add(label + ": the rounded price is 0, so this is now a free shop.");
            }
        }
        if (!Prices.isValid(rounded)) {
            throw new IllegalArgumentException("the price " + original.toPlainString() + " is outside 0 to " + Prices.MAX_PRICE.toPlainString());
        }
        return rounded;
    }

    /**
     * v1 stored an item as base64 of compressed NBT. Very old v1 shops only have an item id.
     * v1 never recorded a data version, so the data is assumed to be current.
     */
    private static ItemStack readItem(JsonObject json, HolderLookup.Provider registries) {
        ItemStack item;
        try {
            item = decodeItem(json, registries);
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException("its item could not be read (" + e.getMessage() + ")");
        }
        if (item.isEmpty()) {
            throw new IllegalArgumentException("it has no item");
        }
        return item;
    }

    private static ItemStack decodeItem(JsonObject json, HolderLookup.Provider registries) throws IOException {
        JsonElement encoded = json.get("itemStackSnbt");
        if (encoded != null && encoded.isJsonPrimitive() && !encoded.getAsString().isEmpty()) {
            byte[] bytes = Base64.getDecoder().decode(encoded.getAsString());
            CompoundTag nbt = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), nbt).getOrThrow();
        }
        String itemId = text(json, "itemId");
        Item found = BuiltInRegistries.ITEM.get(Identifier.parse(itemId)).map(Holder.Reference::value).orElse(Items.AIR);
        return new ItemStack(found);
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("missing or invalid field: " + key);
        }
        return element.getAsJsonObject();
    }

    private static String text(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing or invalid field: " + key);
        }
        return element.getAsString();
    }

    private static int whole(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing or invalid field: " + key);
        }
        return element.getAsBigDecimal().intValueExact();
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("missing or invalid field: " + key);
        }
        return element.getAsBoolean();
    }
}
