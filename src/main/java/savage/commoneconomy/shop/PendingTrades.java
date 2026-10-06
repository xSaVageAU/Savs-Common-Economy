package savage.commoneconomy.shop;

import savage.commoneconomy.shop.model.BlockLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The trades players have started by clicking a shop's sign and have not yet finished by typing an amount (D9).
 * Only the shop's identity is kept, dimension included, never the shop itself, so a shop removed in the meantime
 * is noticed when the amount arrives. A pending trade runs out after 30 seconds and is checked when the player
 * next chats. Holds plain values only, and takes the time as a parameter so it can be checked without the game.
 * Call it from the server thread only.
 */
final class PendingTrades {

    static final long EXPIRY_MILLIS = 30_000;

    private final Map<UUID, Pending> pending = new HashMap<>();

    /**
     * @param shop the anchor of the shop the player clicked
     */
    record Pending(BlockLocation shop, long startedAt) {
    }

    /**
     * Starts a pending trade for the player, replacing any earlier one.
     */
    void start(UUID player, BlockLocation shop, long now) {
        pending.put(player, new Pending(shop, now));
    }

    /**
     * @return the player's pending trade, or null if there is none or it has run out (which also forgets it)
     */
    Pending active(UUID player, long now) {
        Pending found = pending.get(player);
        if (found != null && now - found.startedAt() > EXPIRY_MILLIS) {
            pending.remove(player);
            return null;
        }
        return found;
    }

    void end(UUID player) {
        pending.remove(player);
    }
}
