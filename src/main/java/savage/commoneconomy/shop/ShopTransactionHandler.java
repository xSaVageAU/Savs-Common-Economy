package savage.commoneconomy.shop;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

public class ShopTransactionHandler {
    
    public static void handleBuyTransaction(World world, ServerPlayerEntity player, Shop shop, int amount) {
        
        // Check if shop has enough stock
        if (!shop.canSell(amount)) {
            player.sendMessage(Text.literal("§cShop doesn't have enough stock!"), false);
            return;
        }
        
        // Calculate total price
        BigDecimal totalPrice = shop.getPrice().multiply(BigDecimal.valueOf(amount));
        
        // Check if player has enough money
        if (EconomyManager.getInstance().getBalance(player.getUuid()).compareTo(totalPrice) < 0) {
            player.sendMessage(Text.literal("§cYou don't have enough money! Need " + 
                    EconomyManager.getInstance().format(totalPrice)), false);
            return;
        }
        
        // Get chest inventory
        BlockEntity blockEntity = world.getBlockEntity(shop.getChestLocation());
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            player.sendMessage(Text.literal("§cShop chest not found!"), false);
            return;
        }
        
        // Remove items from chest (or just decrement stock for admin shops)
        if (!shop.isAdmin()) {
            int removed = removeItemsFromInventory(chest, shop.getItem(), amount);
            if (removed < amount) {
                player.sendMessage(Text.literal("§cShop doesn't have enough items in chest!"), false);
                return;
            }
            shop.removeStock(removed);
        }
        
        // Transfer money
        EconomyManager.getInstance().removeBalance(player.getUuid(), totalPrice);
        if (!shop.isAdmin()) {
            EconomyManager.getInstance().addBalance(shop.getOwnerId(), totalPrice);
            
            // Publish Redis notification to shop owner if they're on another server
            try {
                BigDecimal ownerBalance = EconomyManager.getInstance().getBalance(shop.getOwnerId());
                savage.commoneconomy.util.RedisManager.getInstance().publishTransaction(
                    shop.getOwnerId(),
                    ownerBalance,
                    "shop_buy",
                    player.getName().getString(),
                    "Received " + EconomyManager.getInstance().format(totalPrice) + " from shop sale"
                );
            } catch (Exception e) {
                // Redis is optional
            }
        }
        
        // Publish Redis notification to buyer (silent update for cache invalidation)
        try {
            BigDecimal buyerBalance = EconomyManager.getInstance().getBalance(player.getUuid());
            savage.commoneconomy.util.RedisManager.getInstance().publishTransaction(
                player.getUuid(),
                buyerBalance,
                "shop_buy",
                "Shop",
                null // Silent update
            );
        } catch (Exception e) {
            // Redis is optional
        }
        
        // Give items to player
        ItemStack itemToGive = shop.getItem().copy();
        itemToGive.setCount(amount);
        player.getInventory().offerOrDrop(itemToGive);
        
        // Update sign
        BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
        if (signPos != null) {
            ShopSignHelper.updateSignText(world, signPos, shop);
        }
        
        // Save shop data
        ShopManager.getInstance().save();
        
        player.sendMessage(Text.literal("§aBought " + amount + "x " + shop.getItem().getName().getString() + 
                " for " + EconomyManager.getInstance().format(totalPrice)), false);
        
        String targetName = shop.isAdmin() ? "Admin Shop" : shop.getOwnerId().toString();
        savage.commoneconomy.util.TransactionLogger.log("SHOP_BUY", player.getName().getString(), targetName, totalPrice, "Bought " + amount + "x " + shop.getItem().getName().getString());
    }
    
    public static void handleSellTransaction(World world, ServerPlayerEntity player, Shop shop, int amount) {
        
        // Check if player has the items
        int playerHas = countItemInInventory(player, shop.getItem());
        if (playerHas < amount) {
            player.sendMessage(Text.literal("§cYou don't have enough items!"), false);
            return;
        }
        
        // Calculate total price
        BigDecimal totalPrice = shop.getPrice().multiply(BigDecimal.valueOf(amount));
        
        // Check if shop owner has enough money (unless admin shop)
        if (!shop.isAdmin()) {
            if (EconomyManager.getInstance().getBalance(shop.getOwnerId()).compareTo(totalPrice) < 0) {
                player.sendMessage(Text.literal("§cShop owner doesn't have enough money!"), false);
                return;
            }
        }
        
        // Get chest inventory
        BlockEntity blockEntity = world.getBlockEntity(shop.getChestLocation());
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            player.sendMessage(Text.literal("§cShop chest not found!"), false);
            return;
        }
        
        // Check if chest has space
        if (!shop.isAdmin() && !hasSpaceForItems(chest, shop.getItem(), amount)) {
            player.sendMessage(Text.literal("§cShop chest is full!"), false);
            return;
        }
        
        // Remove items from player
        int removed = removeItemsFromPlayer(player, shop.getItem(), amount);
        if (removed < amount) {
            player.sendMessage(Text.literal("§cFailed to remove items from inventory!"), false);
            return;
        }
        
        // Add items to chest (unless admin shop)
        if (!shop.isAdmin()) {
            addItemsToInventory(chest, shop.getItem(), amount);
            shop.addStock(amount);
        }
        
        // Transfer money
        if (!shop.isAdmin()) {
            EconomyManager.getInstance().removeBalance(shop.getOwnerId(), totalPrice);
        }
        EconomyManager.getInstance().addBalance(player.getUuid(), totalPrice);
        
        // Publish Redis notification to player if they're on another server
        try {
            BigDecimal playerBalance = EconomyManager.getInstance().getBalance(player.getUuid());
            savage.commoneconomy.util.RedisManager.getInstance().publishTransaction(
                player.getUuid(),
                playerBalance,
                "shop_sell",
                shop.isAdmin() ? "Admin Shop" : "Shop",
                "Received " + EconomyManager.getInstance().format(totalPrice) + " from selling items"
            );
        } catch (Exception e) {
            // Redis is optional
        }
        
        // Update sign
        BlockPos signPos = ShopSignHelper.findSignForChest(world, shop.getChestLocation());
        if (signPos != null) {
            ShopSignHelper.updateSignText(world, signPos, shop);
        }
        
        // Save shop data
        ShopManager.getInstance().save();
        
        player.sendMessage(Text.literal("§aSold " + amount + "x " + shop.getItem().getName().getString() + 
                " for " + EconomyManager.getInstance().format(totalPrice)), false);

        String sourceName = shop.isAdmin() ? "Admin Shop" : shop.getOwnerId().toString();
        savage.commoneconomy.util.TransactionLogger.log("SHOP_SELL", sourceName, player.getName().getString(), totalPrice, "Sold " + amount + "x " + shop.getItem().getName().getString());
    }
    
    private static int countItemInInventory(ServerPlayerEntity player, ItemStack template) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }
    
    private static int removeItemsFromPlayer(ServerPlayerEntity player, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.decrement(toRemove);
                remaining -= toRemove;
            }
        }
        return amount - remaining;
    }
    
    private static int removeItemsFromInventory(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.decrement(toRemove);
                remaining -= toRemove;
            }
        }
        inventory.markDirty();
        return amount - remaining;
    }
    
    private static void addItemsToInventory(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        
        // First, try to stack with existing items
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                int canAdd = Math.min(remaining, template.getMaxCount() - stack.getCount());
                stack.increment(canAdd);
                remaining -= canAdd;
            }
        }
        
        // Then, add to empty slots
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                ItemStack newStack = template.copy();
                int toAdd = Math.min(remaining, template.getMaxCount());
                newStack.setCount(toAdd);
                inventory.setStack(i, newStack);
                remaining -= toAdd;
            }
        }
        
        inventory.markDirty();
    }
    
    private static boolean hasSpaceForItems(Inventory inventory, ItemStack template, int amount) {
        int space = 0;
        
        // Count space in existing stacks
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                space += template.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                space += template.getMaxCount() - stack.getCount();
            }
        }
        
        return space >= amount;
    }
}
