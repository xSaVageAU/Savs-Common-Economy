package savage.commoneconomy.core.feature;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * An optional piece of functionality (bank notes, sell commands, chest shops) that the
 * core can switch on or off. The entrypoint only calls the hooks of enabled features.
 * Config is loaded once at startup, so {@link #isEnabled()} is stable for the whole run.
 */
public interface Feature {

    /** Short name used in log output. */
    String id();

    boolean isEnabled();

    /** Called once during mod initialization, after config is loaded. Register event listeners here. */
    default void onInitialize() {}

    /** Called when commands are being registered. */
    default void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {}

    /** Called when the server is starting. */
    default void onServerStarting(MinecraftServer server) {}
}
