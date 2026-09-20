package savage.commoneconomy.shopv2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which players are in remove-mode (D10): they ran /shop remove and have not yet clicked the shop to remove.
 * It ends after 30 seconds, and when the player leaves. Holds plain values only, and takes the time as a
 * parameter so it can be checked without the game. Call it from the server thread only.
 */
final class RemoveMode {

    static final long EXPIRY_MILLIS = 30_000;

    private final Map<UUID, Long> startedAt = new HashMap<>();

    /**
     * Starts remove-mode for a player who is not in it, and ends it for one who is (v1 behaviour).
     *
     * @return true if the player is now in remove-mode
     */
    boolean toggle(UUID player, long now) {
        if (isActive(player, now)) {
            startedAt.remove(player);
            return false;
        }
        startedAt.put(player, now);
        return true;
    }

    /**
     * An expired entry that has not been swept yet does not count.
     */
    boolean isActive(UUID player, long now) {
        Long started = startedAt.get(player);
        return started != null && now - started <= EXPIRY_MILLIS;
    }

    void end(UUID player) {
        startedAt.remove(player);
    }

    /**
     * @return the players whose remove-mode has run out, which are no longer in it
     */
    List<UUID> removeExpired(long now) {
        List<UUID> expired = new ArrayList<>();
        startedAt.entrySet().removeIf(entry -> {
            boolean isExpired = now - entry.getValue() > EXPIRY_MILLIS;
            if (isExpired) {
                expired.add(entry.getKey());
            }
            return isExpired;
        });
        return expired;
    }
}
