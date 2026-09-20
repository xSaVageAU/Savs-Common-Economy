package savage.commoneconomy.shopv2;

import savage.commoneconomy.shopv2.model.BlockLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * One trade at a time per shop (D6). A trade holds its shop from the moment the amount is submitted until it
 * finishes, whatever the outcome. A lock that has been held for more than 30 seconds is taken to be stuck, for
 * example because a payment never completed, and is freed the next time someone asks for the shop.
 *
 * Every acquisition gets a token, and only the holder of the current token can release, so a stuck trade that
 * finishes late cannot release the lock of the trade that replaced it. Holds plain values only, and takes the
 * time as a parameter so it can be checked without the game. Call it from the server thread only.
 */
final class TradeLocks {

    static final long WATCHDOG_MILLIS = 30_000;

    private record Held(long token, long since) {
    }

    /**
     * @param token           identifies this hold, or 0 if the shop is busy
     * @param stuckLockFreed  true if an earlier hold had run out and was freed to make way, which is worth logging
     */
    record Attempt(long token, boolean stuckLockFreed) {

        boolean acquired() {
            return token != 0;
        }
    }

    private final Map<BlockLocation, Held> held = new HashMap<>();
    private long lastToken;

    Attempt tryAcquire(BlockLocation shop, long now) {
        Held current = held.get(shop);
        boolean stuck = current != null && now - current.since() > WATCHDOG_MILLIS;
        if (current != null && !stuck) {
            return new Attempt(0, false);
        }
        long token = ++lastToken;
        held.put(shop, new Held(token, now));
        return new Attempt(token, stuck);
    }

    /**
     * Releases the shop if the token is still the current hold; otherwise does nothing.
     */
    void release(BlockLocation shop, long token) {
        Held current = held.get(shop);
        if (current != null && current.token() == token) {
            held.remove(shop);
        }
    }
}
