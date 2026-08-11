package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import savage.commoneconomy.util.PermissionsHelper;
import savage.commoneconomy.util.TransactionLogger;
import savage.commoneconomy.util.TranslationHelper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Command for searching and viewing economy transaction logs.
 */
public class LogCommand {

    private static final int RESULTS_PER_PAGE = 6;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ecolog")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            builder.suggest("*");
                            return SharedSuggestionProvider.suggest(context.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder);
                        })
                        .then(Commands.argument("time", IntegerArgumentType.integer(1))
                                .then(Commands.argument("unit", StringArgumentType.string())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"s", "m", "h", "d"}, builder))
                                        .executes(context -> executeLogSearch(context, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> executeLogSearch(context, IntegerArgumentType.getInteger(context, "page"))))))));
    }

    private static int executeLogSearch(CommandContext<CommandSourceStack> context, int page) {
        String target = StringArgumentType.getString(context, "target");
        int time = IntegerArgumentType.getInteger(context, "time");
        String unit = StringArgumentType.getString(context, "unit");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff;

        switch (unit.toLowerCase()) {
            case "s": cutoff = now.minusSeconds(time); break;
            case "m": cutoff = now.minusMinutes(time); break;
            case "h": cutoff = now.minusHours(time); break;
            case "d": cutoff = now.minusDays(time); break;
            default:
                context.getSource().sendFailure(TranslationHelper.translate("command.log.invalid_unit"));
                return 0;
        }

        context.getSource().sendSuccess(() -> TranslationHelper.translate("command.log.searching", target, time, unit), false);

        // Async execution using the centralized executor to avoid blocking server
        savage.commoneconomy.EconomyManager.getInstance().getIoExecutor().submit(() -> {
            List<TransactionLogger.LogEntry> results = TransactionLogger.searchLogs(target, cutoff);
            
            if (results.isEmpty()) {
                context.getSource().sendSuccess(() -> TranslationHelper.translate("command.log.none_found"), false);
                return;
            }

            int totalPages = (int) Math.ceil((double) results.size() / RESULTS_PER_PAGE);
            int currentPage = Math.min(page, totalPages);
            
            context.getSource().sendSuccess(() -> TranslationHelper.translate("command.log.header", results.size(), currentPage, totalPages), false);
            
            int startIndex = (currentPage - 1) * RESULTS_PER_PAGE;
            int endIndex = Math.min(startIndex + RESULTS_PER_PAGE, results.size());
            
            for (int i = startIndex; i < endIndex; i++) {
                TransactionLogger.LogEntry entry = results.get(i);
                
                ChatFormatting typeColor = ChatFormatting.WHITE;
                if (entry.type.contains("PAY")) typeColor = ChatFormatting.GREEN;
                else if (entry.type.contains("ADMIN")) typeColor = ChatFormatting.RED;
                else if (entry.type.contains("SHOP")) typeColor = ChatFormatting.GOLD;
                else if (entry.type.contains("WITHDRAW")) typeColor = ChatFormatting.AQUA;

                MutableComponent logText = Component.empty()
                        .append(Component.literal("[" + entry.timestamp.format(TIME_FORMAT) + "] ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[" + entry.type + "] ")
                                .withStyle(typeColor))
                        .append(Component.literal(entry.source)
                                .withStyle(ChatFormatting.RED))
                        .append(Component.literal(" -> ")
                                .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(entry.target)
                                .withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(": $" + entry.amount.toPlainString() + " ")
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal("(" + entry.reason + ")")
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

                context.getSource().sendSuccess(() -> logText, false);
            }
            
            if (totalPages > 1) {
                MutableComponent navText = Component.empty();
                if (currentPage > 1) {
                    navText.append(TranslationHelper.translate("command.log.nav_prev").copy()
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand( 
                                    "/ecolog " + target + " " + time + " " + unit + " " + (currentPage - 1)))));
                }
                
                if (currentPage < totalPages) {
                    navText.append(TranslationHelper.translate("command.log.nav_next").copy()
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand( 
                                    "/ecolog " + target + " " + time + " " + unit + " " + (currentPage + 1)))));
                }
                context.getSource().sendSuccess(() -> navText, false);
            }
        });

        return 1;
    }
}
