package savage.commoneconomy.shop;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import savage.commoneconomy.EconomyManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages pending shop interactions (buying/selling).
 */
public class ShopInteractionManager {
    private static class Holder {
        static final ShopInteractionManager INSTANCE = new ShopInteractionManager();
    }
    private final Map<UUID, PendingInteraction> pendingInteractions = new HashMap<>();

    private ShopInteractionManager() {}

    public static ShopInteractionManager getInstance() {
        return Holder.INSTANCE;
    }

    public void register() {
        // Shop Interaction (Right-click sign)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            
            // Protect Shop Chests from non-owners
            if (ShopManager.getInstance().isShopChest(pos)) {
                Shop shop = ShopManager.getInstance().getShop(pos);
                if (shop != null) {
                    boolean isOwner = shop.getOwnerId().equals(serverPlayer.getUUID());
                    boolean isAdmin = savage.commoneconomy.util.PermissionsHelper.check(serverPlayer, "savscommoneconomy.admin", 2);
                    
                    if (!isOwner && !isAdmin) {
                        serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.protect.chest"));
                        return InteractionResult.FAIL;
                    }
                }
            }

            if (world.getBlockState(pos).getBlock() instanceof WallSignBlock) {
                BlockPos chestPos = ShopSignHelper.getAttachedChest(world, pos);
                Shop shop = ShopManager.getInstance().getShop(chestPos);

                if (shop != null) {
                    // Remove mode check
                    if (ShopCommands.isInRemoveMode(serverPlayer.getUUID())) {
                        if (shop.getOwnerId().equals(serverPlayer.getUUID()) || savage.commoneconomy.util.PermissionsHelper.check(serverPlayer, "savscommoneconomy.admin", 2)) {
                            ShopManager.getInstance().removeShop(chestPos);
                            world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                            serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.remove.success"));
                            ShopCommands.exitRemoveMode(serverPlayer.getUUID());
                            return InteractionResult.SUCCESS;
                        } else {
                            serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.remove.not_owner"));
                            ShopCommands.exitRemoveMode(serverPlayer.getUUID());
                            return InteractionResult.FAIL;
                        }
                    }

                    // Initiate trade
                    addPendingInteraction(serverPlayer.getUUID(), shop, shop.isBuying());

                    String action = shop.isBuying() ? "sell" : "buy";
                    serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.interaction.prompt_amount", action));
                    serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.interaction.prompt_all", action));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Shop Chat Listener (Amount input)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            PendingInteraction interaction = getPendingInteraction(sender.getUUID());

            if (interaction != null) {
                if (interaction.isExpired()) {
                    removePendingInteraction(sender.getUUID());
                    return true;
                }

                String content = message.signedContent().trim();
                int amount = 0;
                boolean isAll = content.equalsIgnoreCase("all");

                if (!isAll) {
                    try {
                        amount = Integer.parseInt(content);
                        if (amount <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        sender.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.interaction.invalid_amount"));
                        removePendingInteraction(sender.getUUID());
                        return false;
                    }
                }

                Shop shop = interaction.getShop();
                if (interaction.isBuying()) {
                    // Shop buys (Player sells)
                    if (isAll) {
                        amount = 0;
                        for (int i = 0; i < sender.getInventory().getContainerSize(); i++) {
                            ItemStack stack = sender.getInventory().getItem(i);
                            if (ItemStack.isSameItemSameComponents(stack, shop.getItem())) amount += stack.getCount();
                        }
                        if (!shop.isAdmin()) {
                            int shopHasSpace = ShopStockCalculator.calculateStock((ServerLevel)sender.level(), shop);
                            amount = Math.min(amount, shopHasSpace);
                        }
                    }
                    ShopTransactionHandler.handleSale(sender, shop, (ServerLevel)sender.level(), amount);
                } else {
                    // Shop sells (Player buys)
                    if (isAll) {
                        if (shop.isAdmin()) {
                            sender.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.interaction.admin_infinite"));
                            removePendingInteraction(sender.getUUID());
                            return false;
                        }

                        java.math.BigDecimal balance = EconomyManager.getInstance().getCachedBalance(sender.getUUID());
                        int canAfford = balance.divideToIntegralValue(shop.getPrice()).intValue();
                        int shopHas = ShopStockCalculator.calculateStock((ServerLevel)sender.level(), shop);
                        int playerCanFit = ShopTransactionHandler.getAvailableSpace(sender, shop.getItem());
                        amount = Math.min(Math.min(canAfford, shopHas), playerCanFit);
                        if (amount > 2304) amount = 2304;
                    }
                    ShopTransactionHandler.handlePurchase(sender, shop, (ServerLevel)sender.level(), amount);
                }

                removePendingInteraction(sender.getUUID());
                return false; // Consume message
            }
            return true;
        });

        // Block Protection & Sign Destruction Removal
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return true;

            // Protect Shop Chests
            if (ShopManager.getInstance().isShopChest(pos)) {
                serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.protect.break_chest"));
                return false;
            }

            // Handle Sign Breaking
            if (state.getBlock() instanceof WallSignBlock) {
                BlockPos chestPos = ShopSignHelper.getAttachedChest(world, pos);
                Shop shop = ShopManager.getInstance().getShop(chestPos);

                if (shop != null) {
                    boolean isOwner = shop.getOwnerId().equals(serverPlayer.getUUID());
                    boolean isAdmin = savage.commoneconomy.util.PermissionsHelper.check(serverPlayer, "savscommoneconomy.admin", 2);

                    if (isOwner || isAdmin) {
                        ShopManager.getInstance().removeShop(chestPos);
                        serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.remove.sign_broken"));
                        return true; // Allow breaking
                    } else {
                        serverPlayer.sendSystemMessage(savage.commoneconomy.util.TranslationHelper.translate("shop.protect.break_sign"));
                        return false; // Cancel breaking
                    }
                }
            }

            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Dirty Shop Sign Updates (every 1 second)
            // Only processes shops whose chest contents actually changed
            if (server.getTickCount() % 20 == 0) {
                java.util.Set<BlockPos> dirty = ShopManager.getInstance().consumeDirtyShops();
                if (!dirty.isEmpty()) {
                    for (ServerLevel world : server.getAllLevels()) {
                        String worldId = world.dimension().identifier().toString();
                        for (BlockPos chestPos : dirty) {
                            Shop shop = ShopManager.getInstance().getShop(chestPos);
                            if (shop != null && worldId.equals(shop.getWorldId())) {
                                BlockPos signPos = ShopSignHelper.findSignForChest(world, chestPos);
                                if (signPos != null) {
                                    ShopSignHelper.updateSign(world, signPos, shop);
                                }
                            }
                        }
                    }
                    // Persist updated stock values to disk
                    ShopManager.getInstance().save();
                }
            }
            
            // Orphan Shop Cleanup (every 5 seconds)
            if (server.getTickCount() % 100 == 0) {
                java.util.List<BlockPos> toRemove = new java.util.ArrayList<>();
                for (ServerLevel world : server.getAllLevels()) {
                    String worldId = world.dimension().identifier().toString();
                    for (Shop shop : ShopManager.getInstance().getAllShops()) {
                        if (worldId.equals(shop.getWorldId())) {
                            BlockPos chestPos = shop.getChestLocation();
                            if (!(world.getBlockState(chestPos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
                                toRemove.add(chestPos);
                            }
                        }
                    }
                }
                for (BlockPos pos : toRemove) {
                    ShopManager.getInstance().removeShop(pos);
                }
            }
        });
    }

    public void addPendingInteraction(UUID playerId, Shop shop, boolean isBuying) {
        pendingInteractions.put(playerId, new PendingInteraction(shop, isBuying));
    }

    public PendingInteraction getPendingInteraction(UUID playerId) {
        return pendingInteractions.get(playerId);
    }

    public void removePendingInteraction(UUID playerId) {
        pendingInteractions.remove(playerId);
    }

    public static class PendingInteraction {
        private final Shop shop;
        private final boolean isBuying; // true if shop buys (player sells), false if shop sells (player buys)
        private final long timestamp;

        public PendingInteraction(Shop shop, boolean isBuying) {
            this.shop = shop;
            this.isBuying = isBuying;
            this.timestamp = System.currentTimeMillis();
        }

        public Shop getShop() { return shop; }
        public boolean isBuying() { return isBuying; }
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 30000; // 30 seconds expiry
        }
    }
}
