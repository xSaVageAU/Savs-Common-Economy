package savage.commoneconomy.shopv2.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A shop record, the part of a shop that is saved in shops.json (D7).
 * The item is not here; it is stored in its own file named after the shop id.
 * Stock and status are never stored, they are worked out when needed.
 *
 * @param anchor         the container block the shop was created on
 * @param price          the unit price, valid according to {@link Prices}
 * @param sign           where the shop's sign is, or null when there is none
 * @param needsSignLookup true for an imported shop whose sign has not been looked up yet
 */
public record Shop(
        UUID id,
        BlockLocation anchor,
        UUID owner,
        String ownerName,
        ShopType type,
        ShopMode mode,
        BigDecimal price,
        Position sign,
        boolean needsSignLookup
) {

    public Shop {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        if (!Prices.isValid(price)) {
            throw new IllegalArgumentException("Invalid price: " + price);
        }
    }

    public boolean hasSign() {
        return sign != null;
    }

    public Shop withSign(Position newSign) {
        return new Shop(id, anchor, owner, ownerName, type, mode, price, newSign, needsSignLookup);
    }

    public Shop withOwnerName(String newOwnerName) {
        return new Shop(id, anchor, owner, newOwnerName, type, mode, price, sign, needsSignLookup);
    }

    public Shop withType(ShopType newType) {
        return new Shop(id, anchor, owner, ownerName, newType, mode, price, sign, needsSignLookup);
    }

    public Shop withNeedsSignLookup(boolean newNeedsSignLookup) {
        return new Shop(id, anchor, owner, ownerName, type, mode, price, sign, newNeedsSignLookup);
    }
}
