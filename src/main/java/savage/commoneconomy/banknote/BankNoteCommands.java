package savage.commoneconomy.banknote;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.core.EconomyManager;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.core.log.TransactionLogger;
import savage.commoneconomy.core.permissions.PermissionsHelper;

import java.math.BigDecimal;

/**
 * /withdraw: converts balance into a bank note item.
 */
public class BankNoteCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("withdraw")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.withdraw", true))
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(BankNoteCommands::withdraw)));
    }

    private static int withdraw(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        var server = context.getSource().getServer();
        EconomyManager.getInstance().removeBalance(sender.getUUID(), amount).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server.execute(() -> {
                    ItemStack note = BankNote.create(amountDouble);

                    if (!sender.getInventory().add(note)) {
                        sender.drop(note, false, Prediction.SERVER_ONLY);
                    }

                    context.getSource().sendSuccess(() -> TranslationHelper.translate("command.economy.withdraw.success", EconomyManager.getInstance().format(amount)), false);
                    TransactionLogger.log("WITHDRAW", sender.getName().getString(), "Bank Note", amount, "Withdrawal");
                });
            } else {
                context.getSource().sendFailure(TranslationHelper.translate("command.economy.pay.insufficient"));
            }
        });

        return 1;
    }
}
