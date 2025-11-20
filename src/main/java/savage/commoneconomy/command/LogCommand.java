package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import savage.commoneconomy.util.TransactionLogger;

import java.time.LocalDateTime;
import java.util.List;

public class LogCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ecolog")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            // Suggest online players + "*"
                            builder.suggest("*");
                            return net.minecraft.command.CommandSource.suggestMatching(context.getSource().getPlayerNames(), builder);
                        })
                        .then(CommandManager.argument("time", IntegerArgumentType.integer(1))
                                .then(CommandManager.argument("unit", StringArgumentType.string())
                                        .suggests((context, builder) -> net.minecraft.command.CommandSource.suggestMatching(new String[]{"s", "m", "h", "d"}, builder))
                                        .executes(LogCommand::executeLogSearch)))));
    }

    private static int executeLogSearch(CommandContext<ServerCommandSource> context) {
        String target = StringArgumentType.getString(context, "target");
        int time = IntegerArgumentType.getInteger(context, "time");
        String unit = StringArgumentType.getString(context, "unit");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff;

        switch (unit.toLowerCase()) {
            case "s":
                cutoff = now.minusSeconds(time);
                break;
            case "m":
                cutoff = now.minusMinutes(time);
                break;
            case "h":
                cutoff = now.minusHours(time);
                break;
            case "d":
                cutoff = now.minusDays(time);
                break;
            default:
                context.getSource().sendError(Text.literal("Invalid time unit. Use s, m, h, or d."));
                return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("Searching logs for " + target + " in the last " + time + unit + "..."), false);

        // Run search asynchronously to avoid lag
        new Thread(() -> {
            List<String> results = TransactionLogger.searchLogs(target, cutoff);
            
            if (results.isEmpty()) {
                context.getSource().sendFeedback(() -> Text.literal("No transactions found."), false);
                return;
            }

            context.getSource().sendFeedback(() -> Text.literal("--- Found " + results.size() + " transactions ---"), false);
            
            // Limit to last 20 entries to avoid spam
            int limit = 20;
            for (int i = 0; i < Math.min(results.size(), limit); i++) {
                String line = results.get(i);
                context.getSource().sendFeedback(() -> Text.literal(line), false);
            }
            
            if (results.size() > limit) {
                context.getSource().sendFeedback(() -> Text.literal("... and " + (results.size() - limit) + " more."), false);
            }
        }).start();

        return 1;
    }
}
