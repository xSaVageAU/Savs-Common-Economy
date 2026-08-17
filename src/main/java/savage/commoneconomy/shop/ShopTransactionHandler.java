package savage.commoneconomy.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.TranslationHelper;

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
            } else if (getAvailableSpace(player, shop.getItem()) == 0) {
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

        int availableSpace = getAvailableSpace(player, shop.getItem());
        if (availableSpace < amount) {
            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.no_space"));
            return;
        }

        // 2. Asynchronous Fund Removal
        EconomyManager.getInstance().removeBalance(player.getUUID(), totalCost).thenAccept(success -> {
            if (success) {
                // 3. Finalize on Main Thread
                world.getServer().execute(() -> {
                    if (finalizePurchase(player, shop, world, amount)) {
                        // Success! Pay the shop owner (if not admin)
                        if (!shop.isAdmin()) {
                            EconomyManager.getInstance().addBalance(shop.getOwnerId(), totalCost);
                        }
                        Component itemComp = shop.getItem().getHoverName();
                        player.sendSystemMessage(TranslationHelper.translate("shop.transaction.buy_success", amount, itemComp, EconomyManager.getInstance().format(totalCost)));

                        BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
                        if (signPos != null) {
                            ShopSignHelper.updateSign(world, signPos, shop);
                        }
                        ShopManager.getInstance().save();
                    } else {
                        // Refund on failure
                        EconomyManager.getInstance().addBalance(player.getUUID(), totalCost);
                        player.sendSystemMessage(TranslationHelper.translate("shop.transaction.item_transfer_error"));
                    }
                });
            } else {
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.insufficient_funds_detail", EconomyManager.getInstance().format(totalCost)));
            }
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
                EconomyManager.getInstance().addBalance(player.getUUID(), totalPayout);
                Component itemComp = shop.getItem().getHoverName();
                player.sendSystemMessage(TranslationHelper.translate("shop.transaction.sell_admin_success", amount, itemComp, EconomyManager.getInstance().format(totalPayout)));
            }
        } else {
            // Check if shop owner can afford it
            EconomyManager.getInstance().removeBalance(shop.getOwnerId(), totalPayout).thenAccept(success -> {
                if (success) {
                    world.getServer().execute(() -> {
                        if (finalizeSale(player, shop, world, finalAmount)) {
                            // Pay the seller
                            EconomyManager.getInstance().addBalance(player.getUUID(), finalPayout);
                            
                            Component sellItemComp = shop.getItem().getHoverName();
                            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.sell_success", finalAmount, sellItemComp, EconomyManager.getInstance().format(finalPayout)));

                            BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
                            if (signPos != null) {
                                ShopSignHelper.updateSign(world, signPos, shop);
                            }
                            ShopManager.getInstance().save();
                        } else {
                            // Refund shop owner on failure
                            EconomyManager.getInstance().addBalance(shop.getOwnerId(), finalPayout);
                            player.sendSystemMessage(TranslationHelper.translate("shop.transaction.shop_inventory_error"));
                        }
                    });
                } else {
                    player.sendSystemMessage(TranslationHelper.translate("shop.transaction.owner_out_of_funds"));
                }
            });
        }
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

    public static int getAvailableSpace(ServerPlayer player, ItemStack template) {
        int space = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                space += template.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, template)) {
                space += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return space;
    }
}
