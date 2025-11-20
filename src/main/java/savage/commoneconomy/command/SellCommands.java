package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.Map;

public class SellCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        if (!EconomyManager.getInstance().isSellEnabled()) return;

        dispatcher.register(CommandManager.literal("worth")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.worth", true))
                .executes(SellCommands::checkHandWorth)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::checkAllWorth))
                .then(CommandManager.literal("list")
                        .executes(SellCommands::listWorth))
                .then(CommandManager.argument("item", StringArgumentType.string())
                        .executes(SellCommands::checkItemWorth)));

        dispatcher.register(CommandManager.literal("sell")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::sellAll)));
    }

    private static int checkHandWorth(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack stack = player.getMainHandStack();

        if (stack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        BigDecimal stackValue = price.multiply(BigDecimal.valueOf(stack.getCount()));
        context.getSource().sendFeedback(() -> Text.literal("Worth of " + stack.getCount() + "x " + itemId + ": " + EconomyManager.getInstance().format(stackValue) + " (" + EconomyManager.getInstance().format(price) + " each)"), false);
        return 1;
    }

    private static int checkAllWorth(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack handStack = player.getMainHandStack();

        if (handStack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(handStack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        int totalCount = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalTotalCount = totalCount;
        context.getSource().sendFeedback(() -> Text.literal("Worth of all " + finalTotalCount + "x " + itemId + " in inventory: " + EconomyManager.getInstance().format(totalValue)), false);
        return 1;
    }

    private static int listWorth(CommandContext<ServerCommandSource> context) {
        Map<String, BigDecimal> prices = EconomyManager.getInstance().getAllItemPrices();
        if (prices.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("No items are currently sellable."), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("Sellable Items:"), false);
        for (Map.Entry<String, BigDecimal> entry : prices.entrySet()) {
            context.getSource().sendFeedback(() -> Text.literal("- " + entry.getKey() + ": " + EconomyManager.getInstance().format(entry.getValue())), false);
        }
        return 1;
    }

    private static int checkItemWorth(CommandContext<ServerCommandSource> context) {
        String itemId = StringArgumentType.getString(context, "item");
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("Item '" + itemId + "' cannot be sold or does not exist."));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("Worth of " + itemId + ": " + EconomyManager.getInstance().format(price) + " each"), false);
        return 1;
    }

    private static int sellHand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack stack = player.getMainHandStack();

        if (stack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        int count = stack.getCount();
        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(count));

        player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
        EconomyManager.getInstance().addBalance(player.getUuid(), totalValue);

        context.getSource().sendFeedback(() -> Text.literal("Sold " + count + "x " + itemId + " for " + EconomyManager.getInstance().format(totalValue)), false);
        return 1;
    }

    private static int sellAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack handStack = player.getMainHandStack();

        if (handStack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(handStack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        int totalCount = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        EconomyManager.getInstance().addBalance(player.getUuid(), totalValue);

        int finalTotalCount = totalCount;
        context.getSource().sendFeedback(() -> Text.literal("Sold all " + finalTotalCount + "x " + itemId + " for " + EconomyManager.getInstance().format(totalValue)), false);
        return 1;
    }
}
