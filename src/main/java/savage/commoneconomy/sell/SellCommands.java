package savage.commoneconomy.sell;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.permissions.PermissionsHelper;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.log.TransactionLogger;
import savage.commoneconomy.core.inventory.InventorySpace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Commands for selling/buying items and checking their worth.
 */
public class SellCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /worth
        dispatcher.register(Commands.literal("worth")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.worth", true))
                .executes(SellCommands::checkHandWorth)
                .then(Commands.literal("all")
                        .executes(SellCommands::checkAllWorth))
                .then(Commands.literal("list")
                        .executes(SellCommands::listWorth))
                .then(Commands.argument("item", StringArgumentType.string())
                        .executes(SellCommands::checkItemWorth)));

        // /sell
        dispatcher.register(Commands.literal("sell")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(Commands.literal("all")
                        .executes(SellCommands::sellAll)));
                        
        // /buy <item> [amount]
        dispatcher.register(Commands.literal("buy")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.buy", true))
                .then(Commands.argument("item", StringArgumentType.string())
                        .executes(ctx -> buyItem(ctx, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                .executes(ctx -> buyItem(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))));
    }

    private static int checkHandWorth(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.not_holding"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        BigDecimal price = ItemWorth.getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.cannot_be_sold"));
            return 0;
        }

        if (isProtected(stack)) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.protected_item"));
            return 0;
        }

        BigDecimal stackValue = price.multiply(BigDecimal.valueOf(stack.getCount()));
        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_hand", stack.getCount(), itemId, EconomyService.get().format(stackValue), EconomyService.get().format(price)), false);
        return 1;
    }

    private static int checkAllWorth(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack handStack = player.getMainHandItem();

        if (handStack.isEmpty()) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.not_holding"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(handStack.getItem()).toString();
        BigDecimal price = ItemWorth.getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.cannot_be_sold"));
            return 0;
        }

        if (isProtected(handStack)) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.protected_item"));
            return 0;
        }

        int totalCount = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem() && !isProtected(stack)) {
                totalCount += stack.getCount();
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalTotalCount = totalCount;
        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_all", finalTotalCount, itemId, EconomyService.get().format(totalValue)), false);
        return 1;
    }

    private static int listWorth(CommandContext<CommandSourceStack> context) {
        Map<String, BigDecimal> sellPrices = ItemWorth.getAllSellPrices();
        Map<String, BigDecimal> buyPrices = ItemWorth.getAllBuyPrices();
        
        java.util.Set<String> allItems = new java.util.TreeSet<>();
        allItems.addAll(sellPrices.keySet());
        allItems.addAll(buyPrices.keySet());

        if (allItems.isEmpty()) {
            context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_none"), false);
            return 1;
        }

        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_header"), false);
        for (String item : allItems) {
            BigDecimal sell = sellPrices.getOrDefault(item, BigDecimal.ZERO);
            BigDecimal buy = buyPrices.getOrDefault(item, BigDecimal.ZERO);
            
            String sellStr = sell.compareTo(BigDecimal.ZERO) > 0 ? EconomyService.get().format(sell) : "N/A";
            String buyStr = buy.compareTo(BigDecimal.ZERO) > 0 ? EconomyService.get().format(buy) : "N/A";
            
            String buyFormatted = buy.compareTo(BigDecimal.ZERO) > 0 ? "&a" + buyStr : "&c" + buyStr;
            String sellFormatted = sell.compareTo(BigDecimal.ZERO) > 0 ? "&a" + sellStr : "&c" + sellStr;
            
            context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_entry", item, buyFormatted, sellFormatted), false);
        }
        return 1;
    }

    private static int checkItemWorth(CommandContext<CommandSourceStack> context) {
        String itemId = StringArgumentType.getString(context, "item");
        BigDecimal price = ItemWorth.getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.worth_not_found", itemId));
            return 0;
        }

        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.worth_item", itemId, EconomyService.get().format(price)), false);
        return 1;
    }

    private static int sellHand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.not_holding"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        BigDecimal price = ItemWorth.getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.cannot_be_sold"));
            return 0;
        }

        if (isProtected(stack)) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.protected_item"));
            return 0;
        }

        // Take the items now, on the main thread, so the player is paid for exactly what was removed
        // and cannot move or drop them while the payment is in flight.
        int count = stack.getCount();
        ItemStack taken = stack.copy();
        stack.shrink(count);
        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(count));

        var server = context.getSource().getServer();
        EconomyService.get().addBalance(player.getUUID(), totalValue).thenAccept(success -> {
            // Must modify inventory on the main server thread
            server.execute(() -> {
                if (success) {
                    context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.sold_hand", count, itemId, EconomyService.get().format(totalValue)), false);
                    TransactionLogger.log("SELL", player.getName().getString(), "Server", totalValue, "Sold " + count + "x " + itemId);
                } else {
                    giveBack(player, List.of(taken));
                    context.getSource().sendFailure(TranslationHelper.translate("command.sell.transaction_failed"));
                }
            });
        });

        return 1;
    }

    private static int sellAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack handStack = player.getMainHandItem();

        if (handStack.isEmpty()) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.not_holding"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(handStack.getItem()).toString();
        BigDecimal price = ItemWorth.getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.cannot_be_sold"));
            return 0;
        }

        if (isProtected(handStack)) {
            context.getSource().sendFailure(TranslationHelper.translate("command.sell.protected_item"));
            return 0;
        }

        // Take every matching stack now, on the main thread, so the player is paid for exactly what was
        // removed and anything picked up or dropped while the payment is in flight is not affected.
        Item soldItem = handStack.getItem();
        List<ItemStack> taken = new ArrayList<>();
        int totalCount = 0;
        int skippedProtected = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == soldItem) {
                if (isProtected(stack)) {
                    skippedProtected++;
                    continue;
                }
                taken.add(stack.copy());
                totalCount += stack.getCount();
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }

        if (totalCount == 0) return 0;

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalCount = totalCount;
        int finalSkipped = skippedProtected;
        var server = context.getSource().getServer();
        EconomyService.get().addBalance(player.getUUID(), totalValue).thenAccept(success -> {
            // Must modify inventory on the main server thread
            server.execute(() -> {
                if (success) {
                    context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.sold_all", finalCount, itemId, EconomyService.get().format(totalValue)), false);
                    TransactionLogger.log("SELL_ALL", player.getName().getString(), "Server", totalValue, "Sold all " + finalCount + "x " + itemId);
                    if (finalSkipped > 0) {
                        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.sell.skipped_protected", finalSkipped), false);
                    }
                } else {
                    giveBack(player, taken);
                    context.getSource().sendFailure(TranslationHelper.translate("command.sell.transaction_failed"));
                }
            });
        });

        return 1;
    }

    /**
     * Stacks that hold contents or custom data are never sold, because selling would destroy what they hold:
     * shulker boxes and bundles with items inside, and bank notes and other custom-data items.
     */
    private static boolean isProtected(ItemStack stack) {
        if (stack.has(DataComponents.CUSTOM_DATA)) return true;

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItems().iterator().hasNext()) return true;

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    /**
     * Returns items that were taken for a sale whose payment failed. They go into the first free slots,
     * and anything that does not fit is dropped at the player's feet.
     */
    private static void giveBack(ServerPlayer player, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (!player.getInventory().add(item)) {
                player.drop(item, false, Prediction.SERVER_ONLY);
            }
        }
    }

    private static int buyItem(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String itemInput = StringArgumentType.getString(context, "item");
        
        Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(itemInput))
                .orElse(Items.AIR);

        if (item == Items.AIR) {
             context.getSource().sendFailure(TranslationHelper.translate("command.buy.not_found", itemInput));
             return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        BigDecimal price = ItemWorth.getBuyPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(TranslationHelper.translate("command.buy.not_for_sale"));
            return 0;
        }

        if (InventorySpace.getAvailableSpace(player, new ItemStack(item)) < amount) {
            context.getSource().sendFailure(TranslationHelper.translate("command.buy.no_space"));
            return 0;
        }

        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(amount));

        var server3 = context.getSource().getServer();
        EconomyService.get().removeBalance(player.getUUID(), totalCost).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server3.execute(() -> {
                    ItemStack stack = new ItemStack(item, amount);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false, Prediction.SERVER_ONLY);
                    }
                    context.getSource().sendSuccess(() -> TranslationHelper.translate("command.buy.success", amount, itemId, EconomyService.get().format(totalCost)), false);
                    savage.commoneconomy.core.log.TransactionLogger.log("BUY", "Server", player.getName().getString(), totalCost, "Bought " + amount + "x " + itemId);
                });
            } else {
                context.getSource().sendFailure(TranslationHelper.translate("command.buy.insufficient_funds", EconomyService.get().format(totalCost)));
            }
        });

        return 1;
    }
}
