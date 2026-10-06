package savage.commoneconomy.shop.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import savage.commoneconomy.shop.model.Shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The shop records (D7): one JSON file per shop, named after the shop's id, in the shops folder. It has no game
 * dependencies and does no logging: {@link #load()} returns plain-English problems for the caller to report.
 * Call it from the server thread only.
 *
 * <ul>
 *   <li>A save writes only that shop's file, atomically, so nothing that was not loaded can be overwritten.
 *   <li>The previous valid copy of a file is kept next to it as {@code <id>.json.bak}.
 *   <li>A file that cannot be read, or was written by a newer version, is left exactly as it is and reported.
 *   <li>The folder existing is what says the v1 import has been done, so it is only ever created whole: the import
 *       fills a staging folder and moves it into place in one step.
 * </ul>
 */
public final class ShopFolder {

    public static final int FORMAT_VERSION = 1;

    private static final String EXTENSION = ".json";
    private static final String BACKUP_EXTENSION = ".bak";
    private static final String STAGING_SUFFIX = ".importing";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * @param shops      the shops that could be read
     * @param unreadable the ids named by files that could not be read, whose item files are still in use
     * @param problems   plain-English lines describing anything that went wrong, for the caller to log
     */
    public record Loaded(List<Shop> shops, Set<UUID> unreadable, List<String> problems) {
    }

    private final Path folder;

    public ShopFolder(Path folder) {
        this.folder = folder;
    }

    /**
     * Whether the folder exists. The v1 import runs only when it does not, and an empty folder counts as existing, so
     * removing every shop never brings the import back.
     */
    public boolean exists() {
        return Files.isDirectory(folder);
    }

    public Loaded load() throws IOException {
        List<Shop> shops = new ArrayList<>();
        Set<UUID> unreadable = new HashSet<>();
        List<String> problems = new ArrayList<>();
        if (!exists()) {
            return new Loaded(shops, unreadable, problems);
        }

        List<Path> files;
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            read(file, shops, unreadable, problems);
        }
        return new Loaded(shops, unreadable, problems);
    }

    private void read(Path file, List<Shop> shops, Set<UUID> unreadable, List<String> problems) {
        String name = file.getFileName().toString();
        UUID expected;
        try {
            expected = UUID.fromString(name.substring(0, name.length() - EXTENSION.length()));
        } catch (IllegalArgumentException e) {
            problems.add("The file " + name + " in the shops folder is not named after a shop id, so it was ignored.");
            return;
        }

        try {
            JsonObject root = readRoot(file);
            int version = root.get("formatVersion").getAsInt();
            if (version > FORMAT_VERSION) {
                unreadable.add(expected);
                problems.add("The shop file " + name + " was written by a newer version (format " + version + ", this version reads up to "
                        + FORMAT_VERSION + "). It was not loaded and will not be changed.");
                return;
            }
            Shop shop = ShopJson.fromJson(root);
            if (!shop.id().equals(expected)) {
                throw new IllegalArgumentException("the id inside it is " + shop.id());
            }
            shops.add(shop);
        } catch (RuntimeException | IOException e) {
            unreadable.add(expected);
            problems.add("The shop file " + name + " could not be read and was left unchanged: " + e.getMessage());
        }
    }

    /**
     * Saves one shop right away (D7), replacing its file atomically. Keeps the previous copy only if that was valid,
     * so a damaged file can never replace the last good copy.
     */
    public void save(Shop shop) throws IOException {
        Path file = fileFor(shop.id());
        if (Files.exists(file) && isValid(file)) {
            Files.copy(file, backupFor(file), StandardCopyOption.REPLACE_EXISTING);
        }
        AtomicFiles.write(file, toBytes(shop));
    }

    /**
     * Deletes a shop's file and its previous copy. The previous copy goes first, so if that fails the shop's own
     * file is still there and the shop is intact.
     */
    public void delete(UUID shopId) throws IOException {
        Path file = fileFor(shopId);
        Files.deleteIfExists(backupFor(file));
        Files.deleteIfExists(file);
    }

    /**
     * Creates the folder with all of these shops in it, or not at all: the files are written into a staging folder
     * next to it, which is then moved into place in one step. A staging folder left by a crashed import is cleared first.
     *
     * @throws IOException if the folder already exists, or anything could not be written
     */
    public void createAll(Collection<Shop> shops) throws IOException {
        if (exists()) {
            throw new IOException("the shops folder already exists");
        }
        Path staging = folder.resolveSibling(folder.getFileName() + STAGING_SUFFIX);
        deleteStaging(staging);
        Files.createDirectories(staging);
        for (Shop shop : shops) {
            AtomicFiles.write(staging.resolve(shop.id() + EXTENSION), toBytes(shop));
        }
        try {
            Files.move(staging, folder, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, folder);
        }
    }

    private void deleteStaging(Path staging) throws IOException {
        if (!Files.isDirectory(staging)) {
            return;
        }
        try (Stream<Path> leftovers = Files.list(staging)) {
            for (Path leftover : leftovers.toList()) {
                Files.delete(leftover);
            }
        }
        Files.delete(staging);
    }

    private Path fileFor(UUID shopId) {
        return folder.resolve(shopId + EXTENSION);
    }

    private static Path backupFor(Path file) {
        return file.resolveSibling(file.getFileName() + BACKUP_EXTENSION);
    }

    private static byte[] toBytes(Shop shop) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        ShopJson.toJson(shop).entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isValid(Path file) {
        try {
            readRoot(file);
            return true;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    /**
     * @return the parsed file, which is a JSON object with a whole-number format version of at least 1
     * @throws IllegalArgumentException if it is anything else
     */
    private static JsonObject readRoot(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("it is not a JSON object");
        }
        JsonElement version = parsed.getAsJsonObject().get("formatVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsBigDecimal().intValueExact() < 1) {
            throw new IllegalArgumentException("it has no valid formatVersion");
        }
        return parsed.getAsJsonObject();
    }
}
