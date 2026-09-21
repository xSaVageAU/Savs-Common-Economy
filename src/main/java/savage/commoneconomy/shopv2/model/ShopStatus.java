package savage.commoneconomy.shopv2.model;

/**
 * The state of a shop, worked out when needed and never saved (D8). A shop whose container is gone is deleted,
 * and a shop whose file could not be read is not loaded, so neither has a status here.
 */
public enum ShopStatus {
    /** Everything is present. */
    OK("shop.status.ok"),
    /** The container is fine but the recorded sign is missing. */
    NO_SIGN("shop.status.no_sign"),
    /** The container type is no longer allowed, or the dimension cannot be found. */
    DISABLED("shop.status.disabled"),
    /** The shop's item could not be read. */
    UNREADABLE("shop.status.unreadable");

    private final String languageKey;

    ShopStatus(String languageKey) {
        this.languageKey = languageKey;
    }

    public String languageKey() {
        return languageKey;
    }

    /**
     * The problems are ranked: an unreadable item comes before a disabled container, which comes before a missing sign.
     */
    public static ShopStatus of(boolean itemReadable, boolean containerEnabled, boolean hasSign) {
        if (!itemReadable) {
            return UNREADABLE;
        }
        if (!containerEnabled) {
            return DISABLED;
        }
        return hasSign ? OK : NO_SIGN;
    }
}
