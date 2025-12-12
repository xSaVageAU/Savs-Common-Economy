package savage.commoneconomy.util;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;

public class PermissionsHelper {

    private static boolean permissionsApiAvailable;

    static {
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            permissionsApiAvailable = true;
            savage.commoneconomy.SavsCommonEconomy.LOGGER.info("Fabric Permissions API found. Using granular permissions.");
        } catch (ClassNotFoundException e) {
            permissionsApiAvailable = false;
            savage.commoneconomy.SavsCommonEconomy.LOGGER.warn("Fabric Permissions API NOT found. Falling back to vanilla OP levels.");
        }
    }

    public static boolean check(ServerCommandSource source, String node, int level) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(
                        source,
                        node,
                        PermissionLevel.fromLevel(level)
                );
            } catch (Throwable t) {
                savage.commoneconomy.SavsCommonEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to vanilla check.", t);
                return checkVanilla(source, level);
            }
        }
        return checkVanilla(source, level);
    }

    private static boolean checkVanilla(ServerCommandSource source, int level) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(level)));
    }

    public static boolean check(ServerCommandSource source, String node, boolean fallback) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(source, node, fallback);
            } catch (Throwable t) {
                savage.commoneconomy.SavsCommonEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to default.", t);
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean check(net.minecraft.server.network.ServerPlayerEntity player, String node, int level) {
        return check(player.getCommandSource(), node, level);
    }

    public static boolean check(net.minecraft.server.network.ServerPlayerEntity player, String node, boolean fallback) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, fallback);
            } catch (Throwable t) {
                savage.commoneconomy.SavsCommonEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to default.", t);
                return fallback;
            }
        }
        return fallback;
    }
}
