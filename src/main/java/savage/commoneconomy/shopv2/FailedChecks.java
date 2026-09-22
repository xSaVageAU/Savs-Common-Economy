package savage.commoneconomy.shopv2;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which shops most recently failed a periodic check with an exception, so a broken shop is logged once and then
 * skipped instead of being let through to crash the server, and is not logged again on every run while it keeps
 * failing. Holds plain values only. Call it from the server thread only.
 */
final class FailedChecks {

    private final Set<UUID> failing = new HashSet<>();

    /**
     * Notes that a shop's check just failed.
     *
     * @return true the first time this shop is seen failing since it last worked, so the caller should log it;
     *         false on a repeat while it is still failing
     */
    boolean shouldReport(UUID shop) {
        return failing.add(shop);
    }

    /**
     * Notes that a shop's check worked, so a later failure is reported again.
     */
    void clear(UUID shop) {
        failing.remove(shop);
    }
}
