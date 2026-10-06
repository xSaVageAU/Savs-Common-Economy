package savage.commoneconomy.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The guard before a shop whose container is gone is deleted (D8): deleting cannot be undone, so the container
 * must be seen missing twice, at least 4 seconds apart, with no sighting in between. That covers a transient state
 * and a check that happens to run twice in quick succession, for example when a chunk loads just before a sweep.
 * Holds plain values only, and takes the time as a parameter so it can be checked without the game.
 * Call it from the server thread only.
 */
final class MissingContainers {

    static final long MIN_GAP_MILLIS = 4_000;

    private final Map<UUID, Long> firstSeenMissing = new HashMap<>();

    /**
     * Notes that a shop's container was found missing.
     *
     * @return true if the shop should now be deleted, because this is the second sighting
     */
    boolean observeMissing(UUID shop, long now) {
        Long first = firstSeenMissing.get(shop);
        if (first == null) {
            firstSeenMissing.put(shop, now);
            return false;
        }
        return now - first >= MIN_GAP_MILLIS;
    }

    /**
     * Notes that a shop's container is there, which cancels an earlier sighting of it missing.
     */
    void observePresent(UUID shop) {
        firstSeenMissing.remove(shop);
    }

    void forget(UUID shop) {
        firstSeenMissing.remove(shop);
    }
}
