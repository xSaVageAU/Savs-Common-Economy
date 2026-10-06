package savage.commoneconomy.shop;

import savage.commoneconomy.shop.model.ShopStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers the last status reported for each shop, so a problem is reported once, when the status changes, and not
 * on every check (D8). A shop nobody has reported on counts as OK, so a shop that already has a problem when the
 * server starts is reported at its first check. Holds plain values only. Call it from the server thread only.
 */
final class ReportedStatuses {

    private final Map<UUID, ShopStatus> last = new HashMap<>();

    /**
     * Notes a shop's current status.
     *
     * @return true if it differs from the last reported one, so the change should be reported now
     */
    boolean changed(UUID shop, ShopStatus status) {
        ShopStatus before = last.getOrDefault(shop, ShopStatus.OK);
        if (before == status) {
            return false;
        }
        last.put(shop, status);
        return true;
    }

    void forget(UUID shop) {
        last.remove(shop);
    }
}
