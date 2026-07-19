package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.PermissionsHelper;
import savage.commoneconomy.util.TransactionLogger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Administrative economy commands.
 */
public class AdminEconomyCommands {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (context, builder) -> {
        return EconomyManager.getInstance().getAllPlayerNames().thenApply(names -> {
            List<String> suggestions = new ArrayList<>(names);
            suggestions.addAll(Arrays.asList(context.getSource().getServer().getPlayerNames()));
            return SharedSuggestionProvider.suggest(suggestions, builder);
        }).thenCompose(f -> f);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /givemoney <target> <amount>
        dispatcher.register(Commands.literal("givemoney")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminEconomyCommands::giveMoney))));

        // /takemoney <target> <amount>
        dispatcher.register(Commands.literal("takemoney")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminEconomyCommands::takeMoney))));

        // /setmoney <target> <amount>
        dispatcher.register(Commands.literal("setmoney")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminEconomyCommands::setMoney))));

        // /resetmoney <target>
        dispatcher.register(Commands.literal("resetmoney")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        .executes(AdminEconomyCommands::resetMoney)));
    }




    private static int giveMoney(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "amount"));
        
        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(savage.commoneconomy.util.TranslationHelper.translate("command.economy.player_not_found"));
                return;
            }

            EconomyManager.getInstance().addBalance(targetUUID, amount).thenAccept(success -> {
                String formatted = EconomyManager.getInstance().format(amount);
                context.getSource().sendSuccess(() -> savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_gave", formatted, targetName), true);
                
                TransactionLogger.log("ADMIN_GIVE", context.getSource().getTextName(), targetName, amount, "Admin Gift");
                notifyTarget(context, targetUUID, savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_gave_notify", formatted));
            });
        });
        
        return 1;
    }

    private static int takeMoney(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "amount"));
        
        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(savage.commoneconomy.util.TranslationHelper.translate("command.economy.player_not_found"));
                return;
            }

            EconomyManager.getInstance().removeBalance(targetUUID, amount).thenAccept(success -> {
                if (success) {
                    String formatted = EconomyManager.getInstance().format(amount);
                    context.getSource().sendSuccess(() -> savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_took", formatted, targetName), true);
                    TransactionLogger.log("ADMIN_TAKE", context.getSource().getTextName(), targetName, amount, "Admin Take");
                } else {
                    context.getSource().sendFailure(savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_took_insufficient"));
                }
            });
        });
        
        return 1;
    }

    private static int setMoney(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "amount"));
        
        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(savage.commoneconomy.util.TranslationHelper.translate("command.economy.player_not_found"));
                return;
            }

            EconomyManager.getInstance().setBalance(targetUUID, amount).thenAccept(v -> {
                String formatted = EconomyManager.getInstance().format(amount);
                context.getSource().sendSuccess(() -> savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_set", targetName, formatted), true);
                TransactionLogger.log("ADMIN_SET", context.getSource().getTextName(), targetName, amount, "Admin Set");
                notifyTarget(context, targetUUID, savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_set_notify", formatted));
            });
        });
        
        return 1;
    }

    private static int resetMoney(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        
        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(savage.commoneconomy.util.TranslationHelper.translate("command.economy.player_not_found"));
                return;
            }

            EconomyManager.getInstance().resetBalance(targetUUID).thenAccept(v -> {
                BigDecimal defaultBal = savage.commoneconomy.config.ConfigManager.getConfig().defaultBalance;
                String formatted = EconomyManager.getInstance().format(defaultBal);
                context.getSource().sendSuccess(() -> savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_reset", targetName, formatted), true);
                TransactionLogger.log("ADMIN_RESET", context.getSource().getTextName(), targetName, defaultBal, "Admin Reset");
                notifyTarget(context, targetUUID, savage.commoneconomy.util.TranslationHelper.translate("command.economy.admin_reset_notify", formatted));
            });
        });
        
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<UUID> lookupUUID(CommandContext<CommandSourceStack> context, String name) {
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target != null) return java.util.concurrent.CompletableFuture.completedFuture(target.getUUID());
        return EconomyManager.getInstance().getUUIDFromName(name);
    }

    private static void notifyTarget(CommandContext<CommandSourceStack> context, UUID targetUUID, Component message) {
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayer(targetUUID);
        if (target != null) {
            target.sendSystemMessage(message);
        }
    }
}
