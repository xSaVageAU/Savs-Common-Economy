package savage.commoneconomy.core.config;

/**
 * Which chest shop implementation is active. Exactly one is chosen, so the two shop features
 * can never be enabled together. Remove this once the v1 shop is retired.
 */
public enum ShopVersion {
    V1,
    V2;

    /**
     * Reads the shopVersion setting, ignoring case and surrounding spaces.
     * A missing or blank value means the default (V1); any other unrecognised value returns null.
     */
    public static ShopVersion parse(String setting) {
        if (setting == null || setting.isBlank()) return V1;

        return switch (setting.trim().toLowerCase()) {
            case "v1" -> V1;
            case "v2" -> V2;
            default -> null;
        };
    }
}
