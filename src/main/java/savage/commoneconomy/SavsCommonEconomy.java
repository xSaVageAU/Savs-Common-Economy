package savage.commoneconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.command.AdminEconomyCommands;
import savage.commoneconomy.command.EconomyCommands;
import savage.commoneconomy.config.ConfigManager;
import savage.commoneconomy.shop.ShopCommands;
import savage.commoneconomy.shop.ShopInteractionManager;
import savage.commoneconomy.shop.ShopManager;
import savage.commoneconomy.util.TransactionLogger;
import savage.commoneconomy.util.TranslationHelper;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static net.minecraft.server.MinecraftServer server;

	public static net.minecraft.server.MinecraftServer getServer() {
		return server;
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Savs Common Economy is initializing for Minecraft 26.2 (Stable)...");
		
		// Load Configuration
		ConfigManager.load();
		TranslationHelper.initialize();

		// Register Commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			EconomyCommands.register(dispatcher);
			AdminEconomyCommands.register(dispatcher);
			savage.commoneconomy.command.LogCommand.register(dispatcher);
			savage.commoneconomy.command.SellCommands.register(dispatcher);
			if (ConfigManager.getConfig().enableChestShops) {
				ShopCommands.register(dispatcher);
			}
		});

		// Register Player Join Hook
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			EconomyManager.getInstance().getOrCreateAccount(handler.getPlayer().getUUID(), handler.getPlayer().getGameProfile().name());
		});

		// Register Shutdown Hook
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Savs Common Economy is shutting down...");
			EconomyManager.getInstance().shutdown();
			TransactionLogger.shutdown();
		});

		// Listeners
		savage.commoneconomy.listener.BankNoteListener.register();
		
		// Register API Provider
		eu.pb4.common.economy.api.CommonEconomy.register("savs_common_economy", savage.commoneconomy.integration.SavsEconomyProvider.INSTANCE);

		// Initialize Shop System on Server Start (only if enabled)
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			SavsCommonEconomy.server = server;
			if (ConfigManager.getConfig().enableChestShops) {
				ShopManager.getInstance().setServer(server);
				ShopManager.getInstance().load();
				ShopInteractionManager.getInstance().register();
			}
		});
	}
}