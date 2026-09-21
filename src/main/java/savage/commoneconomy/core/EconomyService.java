package savage.commoneconomy.core;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What features (shops, sell, bank notes) are allowed to ask of the economy core.
 * Storage, caching and cross-server sync stay behind this interface.
 *
 * Balance changes run on the economy's IO threads. The returned futures normally
 * complete there, so callbacks must hop back with {@code server.execute(...)}
 * before touching the world or a player's inventory. A negative amount is rejected
 * immediately, so that future may complete on the calling thread instead.
 */
public interface EconomyService {

    static EconomyService get() {
        return EconomyManager.getInstance();
    }

    /**
     * Adds to a player's balance. Completes with true on success.
     */
    CompletableFuture<Boolean> addBalance(UUID uuid, BigDecimal amount);

    /**
     * Removes from a player's balance. Completes with false if the player cannot afford it.
     */
    CompletableFuture<Boolean> removeBalance(UUID uuid, BigDecimal amount);

    /**
     * Balance from the in-memory cache, or the default balance if the account is not cached.
     * Never blocks, so it is safe on the server thread. The default is a guess: use {@link #peekBalance}
     * when a guess would be wrong to act on.
     */
    BigDecimal getCachedBalance(UUID uuid);

    /**
     * The balance if it is known without waiting, or empty if it is not. Unlike {@link #getCachedBalance} it never
     * substitutes the default balance, so a caller can tell a real balance from "not known" and skip a shortcut
     * instead of acting on a guess. Never blocks, so it is safe on the server thread.
     */
    Optional<BigDecimal> peekBalance(UUID uuid);

    /**
     * Formats an amount with the configured currency symbol.
     */
    String format(BigDecimal balance);
}
