package savage.commoneconomy.shopv2.storage;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stores each shop's item as its own SNBT text file, named after the shop's id (D7).
 * The item is the game's own item data with a DataVersion at the root, the way vanilla stamps its files.
 * A file from an older game version is run through the game's data fixer when it is read, then rewritten.
 * A file that cannot be used is never changed. Call it from the server thread only.
 */
public final class ShopItemStore {

    private static final String EXTENSION = ".snbt";
    private static final String DATA_VERSION = "DataVersion";

    public enum Status {
        OK,
        /** The file was written by an older game version, upgraded by the data fixer and rewritten. */
        UPGRADED,
        MISSING,
        /** The file was written by a newer game version; it is left unchanged. */
        NEWER_VERSION,
        /** The file is not valid or the item cannot be decoded; it is left unchanged. */
        UNREADABLE
    }

    /**
     * @param item    the item, only for {@link Status#OK} and {@link Status#UPGRADED}
     * @param problem a plain-English line for the caller to log, or null when there is nothing to report
     */
    public record Loaded(Status status, ItemStack item, String problem) {
    }

    private final Path folder;

    public ShopItemStore(Path folder) {
        this.folder = folder;
    }

    /**
     * Writes the item as a single-item template (D7), replacing any earlier file for the shop.
     */
    public void write(UUID shopId, ItemStack item, HolderLookup.Provider registries) throws IOException {
        AtomicFiles.write(fileFor(shopId), toSnbt(item, registries).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The item as it is written to its file: the single-item template as SNBT text with the current DataVersion.
     * Also lets a deleted shop's item be logged in a form that can be restored by hand.
     */
    public static String toSnbt(ItemStack item, HolderLookup.Provider registries) {
        Tag encoded = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), item.copyWithCount(1))
                .getOrThrow();
        if (!(encoded instanceof CompoundTag tag)) {
            throw new IllegalStateException("An item was not encoded as a compound: " + encoded);
        }
        NbtUtils.addCurrentDataVersion(tag);
        return new SnbtPrinterTagVisitor().visit(tag);
    }

    public Loaded read(UUID shopId, HolderLookup.Provider registries) throws IOException {
        Path file = fileFor(shopId);
        if (!Files.exists(file)) {
            return new Loaded(Status.MISSING, null, "The item file " + file.getFileName() + " is missing.");
        }

        CompoundTag tag;
        try {
            tag = TagParser.parseCompoundFully(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (CommandSyntaxException e) {
            return unreadable(file, "it is not valid SNBT (" + e.getMessage() + ")");
        }

        int fileVersion = NbtUtils.getDataVersion(tag, -1);
        if (fileVersion < 0) {
            return unreadable(file, "it has no DataVersion");
        }
        int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (fileVersion > currentVersion) {
            return new Loaded(Status.NEWER_VERSION, null, "The item file " + file.getFileName()
                    + " was written by a newer game version (data version " + fileVersion + ", running " + currentVersion
                    + ") and was left unchanged.");
        }
        tag.remove(DATA_VERSION);

        boolean upgraded = fileVersion < currentVersion;
        Optional<ItemStack> item;
        String error = null;
        try {
            CompoundTag toDecode = upgraded ? upgrade(tag, fileVersion, currentVersion) : tag;
            DataResult<ItemStack> decoded = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), toDecode);
            item = decoded.result();
            if (item.isEmpty()) {
                error = decoded.error().map(DataResult.Error::message).orElse("unknown error");
            }
        } catch (RuntimeException e) {
            item = Optional.empty();
            error = e.toString();
        }
        if (item.isEmpty()) {
            return unreadable(file, "the item could not be decoded (" + error + ")");
        }

        if (!upgraded) {
            return new Loaded(Status.OK, item.get(), null);
        }
        try {
            write(shopId, item.get(), registries);
            return new Loaded(Status.UPGRADED, item.get(), "The item file " + file.getFileName()
                    + " was upgraded from data version " + fileVersion + " to " + currentVersion + ".");
        } catch (IOException e) {
            return new Loaded(Status.UPGRADED, item.get(), "The item file " + file.getFileName() + " was upgraded in memory from data version "
                    + fileVersion + " to " + currentVersion + " but could not be rewritten: " + e.getMessage());
        }
    }

    public void delete(UUID shopId) throws IOException {
        Files.deleteIfExists(fileFor(shopId));
    }

    /**
     * @return the names of item files that no shop refers to, to be reported and never deleted automatically
     */
    public List<String> findUnused(Collection<UUID> referencedShopIds) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        Set<String> used = referencedShopIds.stream().map(id -> id + EXTENSION).collect(Collectors.toSet());
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION) && !used.contains(name))
                    .sorted()
                    .toList();
        }
    }

    private Path fileFor(UUID shopId) {
        return folder.resolve(shopId + EXTENSION);
    }

    private static CompoundTag upgrade(CompoundTag tag, int from, int to) {
        Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, tag), from, to);
        if (fixed.getValue() instanceof CompoundTag upgraded) {
            return upgraded;
        }
        throw new IllegalStateException("The data fixer did not return an item");
    }

    private static Loaded unreadable(Path file, String reason) {
        return new Loaded(Status.UNREADABLE, null, "The item file " + file.getFileName() + " could not be used: " + reason
                + ". It was left unchanged.");
    }
}
