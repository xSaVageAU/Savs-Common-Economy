package savage.commoneconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.command.EconomyCommands;
import savage.commoneconomy.command.SellCommands;
import savage.commoneconomy.shop.ShopCommands;
import savage.commoneconomy.shop.ShopManager;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Savs Common Economy...");

		// Register Common Economy Provider
		eu.pb4.common.economy.api.CommonEconomy.register("savs_common_economy", savage.commoneconomy.integration.SavsEconomyProvider.INSTANCE);

		LOGGER.info("Savs Common Economy initialized.");

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			EconomyCommands.register(dispatcher);
			SellCommands.register(dispatcher);
			savage.commoneconomy.command.LogCommand.register(dispatcher);
			savage.commoneconomy.command.DebugCommands.register(dispatcher);
			if (EconomyManager.getInstance().getConfig().enableChestShops) {
				ShopCommands.register(dispatcher);
			}
		});

		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().initStorage();
			EconomyManager.getInstance().setServer(server);
			EconomyManager.getInstance().load();
			if (EconomyManager.getInstance().getConfig().enableChestShops) {
				ShopManager.getInstance().load();
			}
			// Initialize Redis connection if enabled
			if (EconomyManager.getInstance().getConfig().redis.enabled) {
				savage.commoneconomy.util.RedisManager redis = savage.commoneconomy.util.RedisManager.getInstance();
				redis.setServer(server);
			}
		});

		// Save economy data when server stops
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			EconomyManager.getInstance().save();
			if (EconomyManager.getInstance().getConfig().enableChestShops) {
				ShopManager.getInstance().save();
			}
		});
		
		// Create account on join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!EconomyManager.getInstance().hasAccount(handler.player.getUuid())) {
				EconomyManager.getInstance().createAccount(handler.player.getUuid(), handler.player.getName().getString());
			}
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
							savage.commoneconomy.util.TransactionLogger.log("DEPOSIT", "Bank Note", player.getName().getString(), value, "Redeemed Note");
							stack.decrement(1);
							return net.minecraft.util.ActionResult.SUCCESS;
						}
					}
				}
			}
			return net.minecraft.util.ActionResult.PASS;
		});
		
		// Register shop features only if enabled
		if (EconomyManager.getInstance().getConfig().enableChestShops) {
			// Register block interaction handler for shop signs
			net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
				if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
				if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer)) {
					return net.minecraft.util.ActionResult.PASS;
				}
				
				net.minecraft.util.math.BlockPos pos = hitResult.getBlockPos();
				net.minecraft.block.BlockState state = world.getBlockState(pos);
				
				// Check if clicked block is a sign
				if (state.getBlock() instanceof net.minecraft.block.WallSignBlock) {
					// Find the chest this sign belongs to
					net.minecraft.util.math.Direction[] directions = {
						net.minecraft.util.math.Direction.NORTH,
						net.minecraft.util.math.Direction.SOUTH,
						net.minecraft.util.math.Direction.EAST,
						net.minecraft.util.math.Direction.WEST
					};
					
					for (net.minecraft.util.math.Direction dir : directions) {
						net.minecraft.util.math.BlockPos chestPos = pos.offset(dir);
						savage.commoneconomy.shop.Shop shop = ShopManager.getInstance().getShop(chestPos);
						
						if (shop != null) {
							// Check if player is in remove mode
							if (ShopCommands.isInRemoveMode(serverPlayer.getUuid())) {
								// Check if player owns the shop or has admin permission
								boolean isOwner = shop.getOwnerId().equals(serverPlayer.getUuid());
								boolean isAdmin = savage.commoneconomy.util.PermissionsHelper.check(serverPlayer, "savscommoneconomy.admin", 2);
								
								if (isOwner || isAdmin) {
									// Remove the shop
									ShopManager.getInstance().removeShop(chestPos);
									savage.commoneconomy.shop.ShopSignHelper.removeSign(world, pos);
									serverPlayer.sendMessage(net.minecraft.text.Text.literal("§aShop removed!"), false);
									ShopCommands.exitRemoveMode(serverPlayer.getUuid());
									return net.minecraft.util.ActionResult.SUCCESS;
								} else {
									serverPlayer.sendMessage(net.minecraft.text.Text.literal("§cYou don't own this shop!"), false);
									ShopCommands.exitRemoveMode(serverPlayer.getUuid());
									return net.minecraft.util.ActionResult.FAIL;
								}
							}
							
							// Handle shop interaction (buy/sell)
							savage.commoneconomy.shop.ShopInteractionManager.getInstance().addPendingInteraction(
								serverPlayer.getUuid(), 
								shop, 
								shop.isBuying() // If shop is buying, player is selling (and vice versa? wait logic check)
							);
							
							// Logic check:
							// Shop buying = true -> Shop wants to buy items -> Player sells to shop
							// Shop buying = false -> Shop wants to sell items -> Player buys from shop
							
							String action = shop.isBuying() ? "sell" : "buy";
							String itemName = shop.getItem().getName().getString();
							String price = EconomyManager.getInstance().format(shop.getPrice());
							
							serverPlayer.sendMessage(net.minecraft.text.Text.literal("§eType the amount you want to " + action + " in chat."), false);
							serverPlayer.sendMessage(net.minecraft.text.Text.literal("§eType 'all' to " + action + " everything."), false);
							
							return net.minecraft.util.ActionResult.SUCCESS;
						}
					}
				}
				
				return net.minecraft.util.ActionResult.PASS;
			});
			
			// Register chat listener for shop interactions
			net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
				savage.commoneconomy.shop.ShopInteractionManager.PendingInteraction interaction = 
					savage.commoneconomy.shop.ShopInteractionManager.getInstance().getPendingInteraction(sender.getUuid());
				
				if (interaction != null) {
					if (interaction.isExpired()) {
						savage.commoneconomy.shop.ShopInteractionManager.getInstance().removePendingInteraction(sender.getUuid());
						return true;
					}
					
					String content = message.getContent().getString().trim();
					int amount = 0;
					boolean isAll = content.equalsIgnoreCase("all");
					
					if (!isAll) {
						try {
							amount = Integer.parseInt(content);
							if (amount <= 0) throw new NumberFormatException();
						} catch (NumberFormatException e) {
							sender.sendMessage(net.minecraft.text.Text.literal("§cInvalid amount! Transaction cancelled."), false);
							savage.commoneconomy.shop.ShopInteractionManager.getInstance().removePendingInteraction(sender.getUuid());
							return false;
						}
					}
					
					savage.commoneconomy.shop.Shop shop = interaction.getShop();
					boolean shopIsBuying = shop.isBuying(); // true = shop buys (player sells), false = shop sells (player buys)
					
					// If shop is buying, player is selling to it
					if (shopIsBuying) {
						if (isAll) {
							// Calculate max items player has
							int count = 0;
							for (int i = 0; i < sender.getInventory().size(); i++) {
								net.minecraft.item.ItemStack stack = sender.getInventory().getStack(i);
								if (net.minecraft.item.ItemStack.areItemsAndComponentsEqual(stack, shop.getItem())) {
									count += stack.getCount();
								}
							}
							amount = count;
						}
						
						if (amount > 0) {
							savage.commoneconomy.shop.ShopTransactionHandler.handleSellTransaction((net.minecraft.server.world.ServerWorld) sender.getEntityWorld(), sender, shop, amount);
						} else {
							sender.sendMessage(net.minecraft.text.Text.literal("§cYou don't have any items to sell!"), false);
						}
					} 
					// If shop is selling, player is buying from it
					else {
						if (isAll) {
							// Calculate max player can afford / shop has
							java.math.BigDecimal balance = EconomyManager.getInstance().getBalance(sender.getUuid());
							int canAfford = balance.divideToIntegralValue(shop.getPrice()).intValue();
							int shopHas = shop.isAdmin() ? Integer.MAX_VALUE : shop.getStock();
							amount = Math.min(canAfford, shopHas);
							
							// Also limit by inventory space (approximate)
							// For now, let's just cap at a reasonable stack limit if 'all' is huge
							if (amount > 2304) amount = 2304; // 36 stacks (full inventory)
						}
						
						if (amount > 0) {
							savage.commoneconomy.shop.ShopTransactionHandler.handleBuyTransaction((net.minecraft.server.world.ServerWorld) sender.getEntityWorld(), sender, shop, amount);
						} else {
							sender.sendMessage(net.minecraft.text.Text.literal("§cYou cannot afford any items or shop is out of stock!"), false);
						}
					}
					
					savage.commoneconomy.shop.ShopInteractionManager.getInstance().removePendingInteraction(sender.getUuid());
					return false; // Cancel chat message
				}
				
				return true;
			});
			
			// Register block break handler to protect shop blocks
			net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
				if (world.isClient()) return true;
				
				// ALWAYS prevent breaking shop chests (even by owner)
				if (state.getBlock() instanceof net.minecraft.block.ChestBlock) {
					if (ShopManager.getInstance().isShopChest(pos)) {
						player.sendMessage(net.minecraft.text.Text.literal("§cYou cannot break shop chests! Remove the shop first with /shop remove."), false);
						return false;
					}
				}
				
				// Check if breaking a shop sign
				if (state.getBlock() instanceof net.minecraft.block.WallSignBlock) {
					net.minecraft.util.math.Direction[] directions = {
						net.minecraft.util.math.Direction.NORTH,
						net.minecraft.util.math.Direction.SOUTH,
						net.minecraft.util.math.Direction.EAST,
						net.minecraft.util.math.Direction.WEST
					};
					
					for (net.minecraft.util.math.Direction dir : directions) {
						net.minecraft.util.math.BlockPos chestPos = pos.offset(dir);
						savage.commoneconomy.shop.Shop shop = ShopManager.getInstance().getShop(chestPos);
						
						if (shop != null) {
							// Only owner or admin can break shop sign
							boolean isOwner = shop.getOwnerId().equals(player.getUuid());
							boolean isAdmin = false;
							if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayerEntity) {
								isAdmin = savage.commoneconomy.util.PermissionsHelper.check(serverPlayerEntity, "savscommoneconomy.admin", 2);
							}
							
							if (!isOwner && !isAdmin) {
								player.sendMessage(net.minecraft.text.Text.literal("§cYou cannot break this shop sign! Use /shop remove instead."), false);
								return false;
							}
							// If owner/admin breaks sign, remove the shop
							ShopManager.getInstance().removeShop(chestPos);
							player.sendMessage(net.minecraft.text.Text.literal("§eShop removed (sign broken)."), false);
							return true;
						}
					}
				}
				
				return true;
			});
			
			// Register server tick end event to clean up orphaned shops (shops without chests)
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
				// Run cleanup every 100 ticks (5 seconds)
				if (server.getTicks() % 100 == 0) {
					java.util.List<net.minecraft.util.math.BlockPos> toRemove = new java.util.ArrayList<>();
					
					// Get all unique shop positions by checking all players' shops
					java.util.Set<savage.commoneconomy.shop.Shop> allShops = new java.util.HashSet<>();
					for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
						for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
							allShops.addAll(ShopManager.getInstance().getPlayerShops(player.getUuid()));
						}
					}
					
					// Check each shop position in its specific world
					for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
						String worldId = world.getRegistryKey().getValue().toString();
						
						for (savage.commoneconomy.shop.Shop shop : allShops) {
							// Only check shops that belong to this world
							if (worldId.equals(shop.getWorldId())) {
								net.minecraft.util.math.BlockPos chestPos = shop.getChestLocation();
								net.minecraft.block.BlockState state = world.getBlockState(chestPos);
								
								// If chest is missing, mark shop for removal
								if (!(state.getBlock() instanceof net.minecraft.block.ChestBlock)) {
									toRemove.add(chestPos);
								}
							}
						}
					}
					
					// Remove orphaned shops
					for (net.minecraft.util.math.BlockPos pos : toRemove) {
						ShopManager.getInstance().removeShop(pos);
					}
					
					if (!toRemove.isEmpty()) {
						ShopManager.getInstance().save();
					}
				}
				
				// Periodic task to update shop signs (every 1 second / 20 ticks)
				if (server.getTicks() % 20 == 0) {
					for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
						String worldId = world.getRegistryKey().getValue().toString();
						
						// Iterate over all shops in this world
						// Note: This is a bit inefficient if there are thousands of shops, but fine for now
						// A better approach would be to have ShopManager store shops by world
						for (savage.commoneconomy.shop.Shop shop : ShopManager.getInstance().getAllShops()) {
							if (worldId.equals(shop.getWorldId())) {
								net.minecraft.util.math.BlockPos signPos = savage.commoneconomy.shop.ShopSignHelper.findSignForChest(world, shop.getChestLocation());
								if (signPos != null) {
									// Calculate current stock
									int currentStock = savage.commoneconomy.shop.ShopStockCalculator.calculateStock(world, shop);
									
									// Only update if stock has changed significantly or hasn't been updated
									// For now, we just call updateSignText which handles the calculation and update
									// Optimization: Check against cached stock before updating sign
									if (currentStock != shop.getStock() && !shop.isAdmin()) {
										savage.commoneconomy.shop.ShopSignHelper.updateSignText(world, signPos, shop);
									}
								}
							}
						}
					}
				}
			});
		}
	}
}