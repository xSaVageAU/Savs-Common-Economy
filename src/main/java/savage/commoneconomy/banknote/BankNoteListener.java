package savage.commoneconomy.banknote;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.core.EconomyManager;
import savage.commoneconomy.core.log.TransactionLogger;
import savage.commoneconomy.core.i18n.TranslationHelper;

import java.math.BigDecimal;
import java.util.OptionalDouble;

/**
 * Handles bank note redemption (right-click to deposit).
 */
public class BankNoteListener {

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide()) {
                var stack = player.getItemInHand(hand);
                OptionalDouble noteValue = BankNote.readValue(stack);
                if (noteValue.isPresent()) {
                    double valueDouble = noteValue.getAsDouble();
                    BigDecimal value = BigDecimal.valueOf(valueDouble);

                    // ANTI-DUPE: Consume the note IMMEDIATELY on the main thread
                    // before going async. This prevents spam-clicking from queuing
                    // multiple addBalance calls for the same note.
                    stack.shrink(1);

                    var server = ((ServerLevel) world).getServer();
                    EconomyManager.getInstance().addBalance(player.getUUID(), value).thenAccept(success -> {
                        server.execute(() -> {
                            if (success) {
                                player.sendSystemMessage(TranslationHelper.translate("listener.banknote.redeemed", EconomyManager.getInstance().format(value))
                                    .copy().withStyle(ChatFormatting.GREEN));
                                TransactionLogger.log("DEPOSIT", "Bank Note", player.getName().getString(), value, "Redeemed Note");
                            } else {
                                // Balance add failed — restore the note to prevent item loss
                                ItemStack restored = BankNote.create(valueDouble);
                                if (!player.getInventory().add(restored)) {
                                    player.drop(restored, false, Prediction.SERVER_ONLY);
                                }
                                player.sendSystemMessage(TranslationHelper.translate("listener.banknote.deposit_failed")
                                    .copy().withStyle(ChatFormatting.RED));
                            }
                        });
                    });

                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });
    }
}
