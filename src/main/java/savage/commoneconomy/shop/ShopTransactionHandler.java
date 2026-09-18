package savage.commoneconomy.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.inventory.InventorySpace;
import savage.commoneconomy.core.log.TransactionLogger;

import java.math.BigDecimal;

/**
 * Executes shop transactions between players and containers.
 */
public class ShopTransactionHandler {

    public static void handlePurchase(ServerPlayer player, Shop shop, net.minecraft.server.level.ServerLevel world,
            int amount) {
        if (amount <= 0) {
            if (!shop.isAdmin() && !shop.canSell(1)) {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.out_of_stock"));
            } else if (InventorySpace.getAvailableSpace(player, shop.getItem()) == 0) {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.no_space"));
            } else {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.insufficient_funds"));
            }
            return;
        }

        BigDecimal unitPrice = shop.getPrice();
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(amount));

        // 1. Initial Checks (Main Thread)
        if (!shop.isAdmin() && !shop.canSell(amount)) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.out_of_stock"));
            return;
        }

        int availableSpace = InventorySpace.getAvailableSpace(player, shop.getItem());
        if (availableSpace < amount) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.no_space"));
            return;
        }

        // 2. Asynchronous Fund Removal
        EconomyService.get().removeBalance(player.getUUID(), totalCost).thenAccept(success -> {
            if (success) {
                // 3. Finalize on Main Thread
                world.getServer().execute(() -> {
                    if (finalizePurchase(player, shop, world, amount)) {
                        // Success! Pay the shop owner (if not admin)
                        if (!shop.isAdmin()) {
                            payShopOwner(player, shop, world, totalCost);
                        }
                        Component itemComp = shop.getItem().getHoverName();
                        player.sendSystemMessage(TranslationHelper.translate("shop.transaction.buy_success", amount, itemComp, EconomyService.get().format(totalCost)));

                        BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
                        if (signPos != null) {
                            ShopSignHelper.updateSign(world, signPos, shop);
                        }
                        ShopManager.getInstance().save();
                    } else {
                        // Refund on failure
                        refundBuyer(player, world, totalCost);
                        player.sendSystemMessage(TranslationHelper.translate("shop.transaction.item_transfer_error"));
                    }
                });
            } else {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.insufficient_funds_detail", EconomyService.get().format(totalCost)));
            }
        });
    }

    /**
     * Pays the shop owner after a completed purchase. The buyer already has the items, so if the
     * deposit fails the owner is owed the money: log what an admin needs to repair it and tell the owner if online.
     */
    private static void payShopOwner(ServerPlayer buyer, Shop shop, ServerLevel world, BigDecimal amount) {
        EconomyService.get().addBalance(shop.getOwnerId(), amount).thenAccept(paid -> {
            if (paid) return;

            world.getServer().execute(() -> {
                String buyerName = buyer.getName().getString();
                SavsCommonEconomy.LOGGER.error("Shop purchase by {} ({}) for {} completed but paying owner {} ({}) failed; the owner is owed this amount.",
                        buyerName, buyer.getUUID(), amount.toPlainString(), shop.getOwnerName(), shop.getOwnerId());
                TransactionLogger.log("TRANSFER_FAILED", buyerName, shop.getOwnerName(), amount, "Shop owner deposit failed so owner is owed this amount");

                ServerPlayer owner = world.getServer().getPlayerList().getPlayer(shop.getOwnerId());
                if (owner != null) {
                    owner.sendSystemMessage(TranslationHelper.translate("shop.transaction.payment_not_credited", EconomyService.get().format(amount)));
                }
            });
        });
    }

    /**
     * Returns the buyer's money after the item transfer failed. The buyer is already told the transaction
     * failed; if the refund itself fails they are owed the money, so log what an admin needs to repair it.
     */
    private static void refundBuyer(ServerPlayer buyer, ServerLevel world, BigDecimal amount) {
        EconomyService.get().addBalance(buyer.getUUID(), amount).thenAccept(refunded -> {
            if (refunded) return;

            world.getServer().execute(() -> {
                String buyerName = buyer.getName().getString();
                SavsCommonEconomy.LOGGER.error("Shop purchase by {} ({}) failed and refunding {} also failed; the buyer is owed this amount.",
                        buyerName, buyer.getUUID(), amount.toPlainString());
                TransactionLogger.log("TRANSFER_FAILED", "Shop", buyerName, amount, "Shop refund failed so buyer is owed this amount");
                buyer.sendSystemMessage(TranslationHelper.translate("shop.transaction.refund_failed"));
            });
        });
    }

    public static void handlePurchase(ServerPlayer player, Shop shop, Level world) {
        handlePurchase(player, shop, (net.minecraft.server.level.ServerLevel) world, 1);
    }

    private static boolean finalizePurchase(ServerPlayer player, Shop shop,
            net.minecraft.server.level.ServerLevel world, int amount) {
        BlockPos chestPos = shop.getChestLocation();
        BlockEntity be = world.getBlockEntity(chestPos);
        ItemStack template = shop.getItem().copy();
        template.setCount(amount);

        if (shop.isAdmin()) {
            // Admin shop: just give items
            player.getInventory().add(template);
            return true;
        }

        if (be instanceof Container container) {
            // Player shop: check and remove from container
            if (removeItemsFromContainer(container, shop.getItem(), amount)) {
                player.getInventory().add(template);
                shop.removeStock(amount);
                container.setChanged();
                return true;
            }
        }
        return false;
    }

    public static void handleSale(ServerPlayer player, Shop shop, net.minecraft.server.level.ServerLevel world,
            int amount) {
        BigDecimal unitPrice = shop.getPrice();
        BigDecimal totalPayout = unitPrice.multiply(BigDecimal.valueOf(amount));
        ItemStack template = shop.getItem();

        // 1. Initial Checks (Main Thread)
        int playerHas = countItems(player, template);
        if (playerHas < amount) {
            amount = playerHas;
            totalPayout = unitPrice.multiply(BigDecimal.valueOf(amount));
        }

        if (amount <= 0) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.no_items"));
            return;
        }

        if (!shop.isAdmin()) {
            int availableSpace = ShopStockCalculator.calculateStock(world, shop);
            if (availableSpace < amount) {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.shop_no_space"));
                return;
            }
        }

        final int finalAmount = amount;
        final BigDecimal finalPayout = totalPayout;

        // 2. Asynchronous Owner Balance Check (If not admin)
        if (shop.isAdmin()) {
            if (finalizeSale(player, shop, world, amount)) {
                paySeller(player, shop, world, totalPayout);
                Component itemComp = shop.getItem().getHoverName();
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.sell_admin_success", amount, itemComp, EconomyService.get().format(totalPayout)));
            }
        } else {
            // Check if shop owner can afford it
            EconomyService.get().removeBalance(shop.getOwnerId(), totalPayout).thenAccept(success -> {
                if (success) {
                    world.getServer().execute(() -> {
                        if (finalizeSale(player, shop, world, finalAmount)) {
                            // Pay the seller
                            paySeller(player, shop, world, finalPayout);
                            
                            Component sellItemComp = shop.getItem().getHoverName();
                            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.sell_success", finalAmount, sellItemComp, EconomyService.get().format(finalPayout)));

                            BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
                            if (signPos != null) {
                                ShopSignHelper.updateSign(world, signPos, shop);
                            }
                            ShopManager.getInstance().save();
                        } else {
                            // Refund shop owner on failure
                            refundShopOwner(player, shop, world, finalPayout);
                            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.shop_inventory_error"));
                        }
                    });
                } else {
                    player.sendSystemMessage(TranslationHelper.translate("shop.transaction.owner_out_of_funds"));
                }
            });
        }
    }

    /**
     * Pays a player who sold items to a shop. The shop already has the items, so if the deposit fails
     * the seller is owed the money: log what an admin needs to repair it and tell the seller.
     */
    private static void paySeller(ServerPlayer seller, Shop shop, ServerLevel world, BigDecimal amount) {
        EconomyService.get().addBalance(seller.getUUID(), amount).thenAccept(paid -> {
            if (paid) return;

            world.getServer().execute(() -> {
                String sellerName = seller.getName().getString();
                String payerName = shop.isAdmin() ? "Shop" : shop.getOwnerName();
                SavsCommonEconomy.LOGGER.error("Shop sale by {} ({}) for {} completed but paying the seller failed; the seller is owed this amount.",
                        sellerName, seller.getUUID(), amount.toPlainString());
                TransactionLogger.log("TRANSFER_FAILED", payerName, sellerName, amount, "Seller deposit failed so seller is owed this amount");
                seller.sendSystemMessage(TranslationHelper.translate("shop.transaction.payment_not_credited", EconomyService.get().format(amount)));
            });
        });
    }

    /**
     * Returns the shop owner's money after the item transfer failed. The seller is already told the transaction
     * failed; if the refund itself fails the owner is owed the money, so log what an admin needs to repair it.
     */
    private static void refundShopOwner(ServerPlayer seller, Shop shop, ServerLevel world, BigDecimal amount) {
        EconomyService.get().addBalance(shop.getOwnerId(), amount).thenAccept(refunded -> {
            if (refunded) return;

            world.getServer().execute(() -> {
                SavsCommonEconomy.LOGGER.error("Shop sale by {} ({}) failed and refunding {} to owner {} ({}) also failed; the owner is owed this amount.",
                        seller.getName().getString(), seller.getUUID(), amount.toPlainString(), shop.getOwnerName(), shop.getOwnerId());
                TransactionLogger.log("TRANSFER_FAILED", "Shop", shop.getOwnerName(), amount, "Shop refund failed so owner is owed this amount");

                ServerPlayer owner = world.getServer().getPlayerList().getPlayer(shop.getOwnerId());
                if (owner != null) {
                    owner.sendSystemMessage(TranslationHelper.translate("shop.transaction.refund_failed"));
                }
            });
        });
    }

    private static boolean finalizeSale(ServerPlayer player, Shop shop, net.minecraft.server.level.ServerLevel world,
            int amount) {
        ItemStack template = shop.getItem();
        if (removeItemsFromPlayer(player, template, amount)) {
            if (shop.isAdmin())
                return true;

            BlockEntity be = world.getBlockEntity(shop.getChestLocation());
            if (be instanceof Container container) {
                ItemStack toAdd = template.copy();
                toAdd.setCount(amount);
                if (addItemToContainer(container, toAdd)) {
                    shop.addStock(amount);
                    container.setChanged();
                    return true;
                }
                // Rollback: return items to player if chest was full
                player.getInventory().add(toAdd);
            }
        }
        return false;
    }

    private static int countItems(ServerPlayer player, ItemStack template) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, template))
                count += stack.getCount();
        }
        return count;
    }

    private static boolean removeItemsFromPlayer(ServerPlayer player, ItemStack template, int amount) {
        for (int i = 0; i < player.getInventory().getContainerSize() && amount > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                int take = Math.min(amount, stack.getCount());
                stack.shrink(take);
                amount -= take;
            }
        }
        return amount == 0;
    }

    private static boolean removeItemsFromContainer(Container container, ItemStack template, int amount) {
        // First check total
        int available = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, template))
                available += stack.getCount();
        }
        if (available < amount)
            return false;

        // Perform removal
        for (int i = 0; i < container.getContainerSize() && amount > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                int take = Math.min(amount, stack.getCount());
                stack.shrink(take);
                amount -= take;
            }
        }
        return true;
    }

    private static boolean addItemToContainer(Container container, ItemStack stack) {
        // Try to stack
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack target = container.getItem(i);
            if (ItemStack.isSameItemSameComponents(target, stack)) {
                int canAdd = Math.min(stack.getCount(), target.getMaxStackSize() - target.getCount());
                if (canAdd > 0) {
                    target.grow(canAdd);
                    stack.shrink(canAdd);
                }
            }
        }
        // Try empty slots
        if (!stack.isEmpty()) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).isEmpty()) {
                    int toInsert = Math.min(stack.getCount(), stack.getMaxStackSize());
                    ItemStack copy = stack.copy();
                    copy.setCount(toInsert);
                    container.setItem(i, copy);
                    stack.shrink(toInsert);
                    if (stack.isEmpty()) {
                        break;
                    }
                }
            }
        }
        return stack.isEmpty();
    }
}
