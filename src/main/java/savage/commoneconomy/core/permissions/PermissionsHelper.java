package savage.commoneconomy.core.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import me.lucko.fabric.api.permissions.v0.Permissions;
import savage.commoneconomy.core.config.ConfigManager;

/**
 * Utility for checking permissions.
 * Integrates with Fabric Permissions API, using OP levels from permissions.json as a fallback default.
 */
public class PermissionsHelper {

    /**
     * Checks if a command source has a specific permission.
     * Uses the required OP level from permissions.json as the fallback.
     */
    public static boolean check(CommandSourceStack source, String permission) {
        int fallbackLevel = ConfigManager.getPermissions().getLevel(permission);
        return Permissions.check(source, permission, fallbackLevel);
    }

    /**
     * Overload that accepts a hardcoded fallback OP level.
     * Used when the permission node isn't in the config.
     */
    public static boolean check(CommandSourceStack source, String permission, int fallbackLevel) {
        int actualLevel = ConfigManager.getPermissions().permissions.getOrDefault(permission, fallbackLevel);
        return Permissions.check(source, permission, actualLevel);
    }

    /**
     * Overload for boolean default (true = anyone, false = op only).
     */
    public static boolean check(CommandSourceStack source, String permission, boolean anyone) {
        if (ConfigManager.getPermissions().permissions.containsKey(permission)) {
            return check(source, permission);
        }
        int fallbackLevel = anyone ? 0 : 2;
        return Permissions.check(source, permission, fallbackLevel);
    }

    public static boolean check(ServerPlayer player, String permission, int fallbackLevel) {
        int actualLevel = ConfigManager.getPermissions().permissions.getOrDefault(permission, fallbackLevel);
        return Permissions.check(player, permission, actualLevel);
    }
}
