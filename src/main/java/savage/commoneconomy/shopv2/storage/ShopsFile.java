package savage.commoneconomy.shopv2.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import savage.commoneconomy.shopv2.model.Shop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads and writes shops.json (D7). It has no game dependencies and does no logging: {@link #load()}
 * returns plain-English problems for the caller to report. Call it from the server thread only.
 *
 * <ul>
 *   <li>Saving is atomic: a temporary file is written and flushed, then moved into place.
 *   <li>The previous valid file is kept as shops.json.bak.
 *   <li>An entry that cannot be read is kept exactly as it was and written back on every save.
 *   <li>A file from a newer format version is never overwritten.
 *   <li>Whenever anything fails to load, a copy of the file is saved as shops.json.broken-{time}.
 * </ul>
 */
public final class ShopsFile {

    public static final int FORMAT_VERSION = 1;

    public enum Status {
        /** There is no file yet. */
        MISSING,
        OK,
        /** Some entries could not be read; they are kept and were left out of the result. */
        PARTLY_UNREADABLE,
        /** The file is not usable at all; nothing was loaded. */
        CORRUPT,
        /** The file was written by a newer version; nothing was loaded and saving is refused. */
        NEWER_FORMAT
    }

    /**
     * @param problems plain-English lines describing anything that went wrong, for the caller to log
     */
    public record Loaded(Status status, List<Shop> shops, List<String> problems) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final List<JsonElement> unreadable = new ArrayList<>();
    private boolean readOnly;

    public ShopsFile(Path file) {
        this.file = file;
    }

    public Loaded load() throws IOException {
        unreadable.clear();
        readOnly = false;

        if (!Files.exists(file)) {
            return new Loaded(Status.MISSING, List.of(), List.of());
        }

        List<String> problems = new ArrayList<>();
        JsonObject root = readRoot(file);
        if (root == null) {
            return corrupt(problems);
        }

        // Checked before anything else: a newer format may lay the rest out differently
        int version = root.get("formatVersion").getAsInt();
        if (version > FORMAT_VERSION) {
            readOnly = true;
            problems.add("shops.json was written by a newer version (format " + version + ", this version reads up to "
                    + FORMAT_VERSION + "). It was not loaded and will not be changed.");
            return new Loaded(Status.NEWER_FORMAT, List.of(), problems);
        }

        if (!root.has("shops") || !root.get("shops").isJsonArray()) {
            return corrupt(problems);
        }

        List<Shop> shops = new ArrayList<>();
        for (JsonElement entry : root.getAsJsonArray("shops")) {
            try {
                shops.add(ShopJson.fromJson(entry.getAsJsonObject()));
            } catch (RuntimeException e) {
                unreadable.add(entry);
                problems.add("A shop entry could not be read and is kept unchanged: " + e.getMessage());
            }
        }

        if (unreadable.isEmpty()) {
            return new Loaded(Status.OK, shops, problems);
        }
        backUp(problems);
        return new Loaded(Status.PARTLY_UNREADABLE, shops, problems);
    }

    /**
     * Writes the shops, plus every entry that could not be read when the file was loaded.
     *
     * @return false if nothing was written because the file is from a newer format
     */
    public boolean save(Collection<Shop> shops) throws IOException {
        if (readOnly) {
            return false;
        }

        JsonArray entries = new JsonArray();
        for (Shop shop : shops) {
            entries.add(ShopJson.toJson(shop));
        }
        for (JsonElement entry : unreadable) {
            entries.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.add("shops", entries);

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        writeAndFlush(temp, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        keepPreviousCopy();
        moveIntoPlace(temp);
        return true;
    }

    /**
     * @return how many entries could not be read at the last load
     */
    public int unreadableCount() {
        return unreadable.size();
    }

    /**
     * @return the parsed file, or null if it is not a JSON object with a usable format version
     */
    private static JsonObject readRoot(Path path) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonElement version = parsed.getAsJsonObject().get("formatVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || version.getAsBigDecimal().intValueExact() < 1) {
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException | ArithmeticException e) {
            return null;
        }
    }

    private Loaded corrupt(List<String> problems) {
        problems.add("shops.json is not valid, so no shops were loaded.");
        backUp(problems);
        return new Loaded(Status.CORRUPT, List.of(), problems);
    }

    private void backUp(List<String> problems) {
        Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(file, backup);
            problems.add("A copy of the original was saved as " + backup.getFileName() + ".");
        } catch (IOException e) {
            problems.add("A backup copy of the original could not be saved: " + e.getMessage());
        }
    }

    /**
     * Only a file that is valid is copied over the previous backup, so a damaged file
     * can never replace the last good copy.
     */
    private void keepPreviousCopy() throws IOException {
        if (Files.exists(file) && readRoot(file) != null) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAndFlush(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
