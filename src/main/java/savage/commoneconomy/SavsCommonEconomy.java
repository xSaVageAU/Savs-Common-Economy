package savage.commoneconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.command.EconomyCommands;
import savage.commoneconomy.command.SellCommands;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Savs Common Economy...");

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            EconomyCommands.register(dispatcher);
            SellCommands.register(dispatcher);
        });

		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().load();
		});

		// Save economy data when server stops
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			EconomyManager.getInstance().save();
		});
		
		// Create account on join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			EconomyManager.getInstance().createAccount(handler.player.getUuid(), handler.player.getName().getString());
		});
		
		// Register right-click handler for bank notes
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!world.isClient()) {
				net.minecraft.item.ItemStack stack = player.getStackInHand(hand);
				if (stack.getItem() == net.minecraft.item.Items.PAPER) {
					net.minecraft.component.type.NbtComponent nbtComponent = stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
					if (nbtComponent != null) {
						net.minecraft.nbt.NbtCompound nbt = nbtComponent.copyNbt();
						if (nbt.contains("EconomyBankNote") && nbt.contains("Value")) {
							double valueDouble = nbt.getDouble("Value").orElse(0.0);
							java.math.BigDecimal value = java.math.BigDecimal.valueOf(valueDouble);
							EconomyManager.getInstance().addBalance(player.getUuid(), value);
							player.sendMessage(net.minecraft.text.Text.literal("Redeemed bank note for " + EconomyManager.getInstance().format(value)).formatted(net.minecraft.util.Formatting.GREEN), true);
							stack.decrement(1);
							return net.minecraft.util.ActionResult.SUCCESS;
						}
					}
				}
			}
			return net.minecraft.util.ActionResult.PASS;
		});
	}
}