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

    private static final int RESULTS_PER_PAGE = 6;

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
                                        .executes(context -> executeLogSearch(context, 1))
                                        .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> executeLogSearch(context, IntegerArgumentType.getInteger(context, "page"))))))));
    }

    private static int executeLogSearch(CommandContext<ServerCommandSource> context, int page) {
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
            List<TransactionLogger.LogEntry> results = TransactionLogger.searchLogs(target, cutoff);
            
            if (results.isEmpty()) {
                context.getSource().sendFeedback(() -> Text.literal("No transactions found."), false);
                return;
            }

            int totalPages = (int) Math.ceil((double) results.size() / RESULTS_PER_PAGE);
            int currentPage = Math.min(page, totalPages);
            
            context.getSource().sendFeedback(() -> Text.literal("--- Found " + results.size() + " transactions (Page " + currentPage + "/" + totalPages + ") ---"), false);
            
            int startIndex = (currentPage - 1) * RESULTS_PER_PAGE;
            int endIndex = Math.min(startIndex + RESULTS_PER_PAGE, results.size());
            
            for (int i = startIndex; i < endIndex; i++) {
                TransactionLogger.LogEntry entry = results.get(i);
                
                // Format: [Time] [TYPE] Source -> Target: $Amount (Details)
                // Colors: Time=Gray, Type=Color, Source/Target=White, Amount=Yellow, Details=Gray Italic
                
                net.minecraft.util.Formatting typeColor = net.minecraft.util.Formatting.WHITE;
                if (entry.type.contains("PAY")) typeColor = net.minecraft.util.Formatting.GREEN;
                else if (entry.type.contains("ADMIN")) typeColor = net.minecraft.util.Formatting.RED;
                else if (entry.type.contains("SHOP")) typeColor = net.minecraft.util.Formatting.GOLD;
                else if (entry.type.contains("WITHDRAW")) typeColor = net.minecraft.util.Formatting.AQUA;

                Text logText = Text.empty()
                        .append(Text.literal("[" + entry.timestamp.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] ")
                                .formatted(net.minecraft.util.Formatting.GRAY))
                        .append(Text.literal("[" + entry.type + "] ")
                                .formatted(typeColor))
                        .append(Text.literal(entry.source)
                                .formatted(net.minecraft.util.Formatting.RED))
                        .append(Text.literal(" -> ")
                                .formatted(net.minecraft.util.Formatting.WHITE))
                        .append(Text.literal(entry.target)
                                .formatted(net.minecraft.util.Formatting.GREEN))
                        .append(Text.literal(": $" + entry.amount.toPlainString() + " ")
                                .formatted(net.minecraft.util.Formatting.YELLOW))
                        .append(Text.literal("(" + entry.details + ")")
                                .formatted(net.minecraft.util.Formatting.GRAY, net.minecraft.util.Formatting.ITALIC));

                context.getSource().sendFeedback(() -> logText, false);
            }
            
            // Navigation buttons
            net.minecraft.text.MutableText navText = Text.empty();
            if (currentPage > 1) {
                navText.append(Text.literal("[< Previous] ")
                        .formatted(net.minecraft.util.Formatting.AQUA, net.minecraft.util.Formatting.BOLD)
                        .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand(
                                "/ecolog " + target + " " + time + " " + unit + " " + (currentPage - 1)))));
            }
            
            if (currentPage < totalPages) {
                navText.append(Text.literal("[Next >]")
                        .formatted(net.minecraft.util.Formatting.AQUA, net.minecraft.util.Formatting.BOLD)
                        .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand(
                                "/ecolog " + target + " " + time + " " + unit + " " + (currentPage + 1)))));
            }
            
            if (totalPages > 1) {
                context.getSource().sendFeedback(() -> navText, false);
            }

        }).start();

        return 1;
    }
}
