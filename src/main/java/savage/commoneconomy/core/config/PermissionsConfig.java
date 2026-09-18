package savage.commoneconomy.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable permission levels for all commands and actions.
 * Stored in permissions.json.
 * 
 * Values represent the minimum OP level required:
 *   0 = anyone (no OP required)
 *   2 = gamemaster (OP level 2)
 *   4 = admin (OP level 4)
 * 
 * When a permissions mod (e.g. LuckPerms + Fabric Permissions API) is available,
 * these act as fallback defaults for players without explicit permission nodes.
 */
public class PermissionsConfig {
    // Using LinkedHashMap to preserve insertion order in JSON output
    public Map<String, Integer> permissions = new LinkedHashMap<>();

    public PermissionsConfig() {
        // Economy commands (default: anyone)
        permissions.put("savscommoneconomy.command.bal", 0);
        permissions.put("savscommoneconomy.command.bal.others", 0);
        permissions.put("savscommoneconomy.command.pay", 0);
        permissions.put("savscommoneconomy.command.withdraw", 0);
        permissions.put("savscommoneconomy.command.baltop", 0);

        // Trading commands (default: anyone)
        permissions.put("savscommoneconomy.command.worth", 0);
        permissions.put("savscommoneconomy.command.sell", 0);
        permissions.put("savscommoneconomy.command.buy", 0);

        // Shop commands (default: anyone)
        permissions.put("savscommoneconomy.shop.create", 0);
        permissions.put("savscommoneconomy.shop.info", 0);
        permissions.put("savscommoneconomy.shop.list", 0);
        permissions.put("savscommoneconomy.shop.remove", 0);

        // Admin commands (default: OP level 2 / gamemaster)
        permissions.put("savscommoneconomy.admin", 2);
    }

    /**
     * Gets the OP level for a permission node.
     * Returns 0 (anyone) if the node isn't defined.
     */
    public int getLevel(String permission) {
        return permissions.getOrDefault(permission, 0);
    }
}
