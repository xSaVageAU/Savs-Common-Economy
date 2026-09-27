package savage.commoneconomy;

import eu.pb4.common.economy.api.CommonEconomy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.banknote.BankNoteFeature;
import savage.commoneconomy.core.EconomyManager;
import savage.commoneconomy.core.api.SavsEconomyProvider;
import savage.commoneconomy.core.command.AdminEconomyCommands;
import savage.commoneconomy.core.command.EconomyCommands;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.feature.Feature;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.log.LogCommand;
import savage.commoneconomy.core.log.TransactionLogger;
import savage.commoneconomy.sell.SellFeature;
import savage.commoneconomy.shopv2.ShopV2Feature;

import java.util.List;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static MinecraftServer server;

	private static final List<Feature> FEATURES = List.of(
			new BankNoteFeature(),
			new SellFeature(),
			new ShopV2Feature()
	);

	public static MinecraftServer getServer() {
		return server;
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Savs Common Economy is initializing for Minecraft 26.3 (Stable)...");

		// Load Configuration
		ConfigManager.load();
		TranslationHelper.initialize();

		for (Feature feature : FEATURES) {
			LOGGER.info("Feature '{}': {}", feature.id(), feature.isEnabled() ? "enabled" : "disabled");
		}

		// Register Commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			EconomyCommands.register(dispatcher);
			AdminEconomyCommands.register(dispatcher);
			LogCommand.register(dispatcher);
			for (Feature feature : FEATURES) {
				if (feature.isEnabled()) {
					feature.registerCommands(dispatcher);
				}
			}
		});

		// Register Player Join Hook
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			EconomyManager.getInstance().getOrCreateAccount(handler.getPlayer().getUUID(), handler.getPlayer().getGameProfile().name());
		});

		// Register Shutdown Hook
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Savs Common Economy is shutting down...");
			EconomyManager.getInstance().shutdown();
			TransactionLogger.shutdown();
		});

		// Feature listeners
		for (Feature feature : FEATURES) {
			if (feature.isEnabled()) {
				feature.onInitialize();
			}
		}

		// Register API Provider
		CommonEconomy.register("savs_common_economy", SavsEconomyProvider.INSTANCE);

		// Feature startup on server start (only enabled features)
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			SavsCommonEconomy.server = server;
			for (Feature feature : FEATURES) {
				if (feature.isEnabled()) {
					feature.onServerStarting(server);
				}
			}
		});
	}
}
