package savage.commoneconomy.listener;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.TransactionLogger;
import savage.commoneconomy.util.TranslationHelper;

import java.math.BigDecimal;

/**
 * Handles bank note redemption (right-click to deposit).
 */
public class BankNoteListener {

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide()) {
                var stack = player.getItemInHand(hand);
                if (stack.is(Items.PAPER)) {
                    CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
                    if (customData != null) {
                        CompoundTag tag = customData.copyTag();
                        if (tag.contains("EconomyBankNote") && tag.contains("Value")) {
                            double valueDouble = tag.getDouble("Value").orElse(0.0);
                            BigDecimal value = BigDecimal.valueOf(valueDouble);
                            
                            // ANTI-DUPE: Consume the note IMMEDIATELY on the main thread
                            // before going async. This prevents spam-clicking from queuing
                            // multiple addBalance calls for the same note.
                            stack.shrink(1);
                            
                            var server = ((net.minecraft.server.level.ServerLevel) world).getServer();
                            EconomyManager.getInstance().addBalance(player.getUUID(), value).thenAccept(success -> {
                                server.execute(() -> {
                                    if (success) {
                                        player.sendSystemMessage(TranslationHelper.translate("listener.banknote.redeemed", EconomyManager.getInstance().format(value))
                                            .copy().withStyle(ChatFormatting.GREEN));
                                        TransactionLogger.log("DEPOSIT", "Bank Note", player.getName().getString(), value, "Redeemed Note");
                                    } else {
                                        // Balance add failed — restore the note to prevent item loss
                                        ItemStack restored = new ItemStack(Items.PAPER);
                                        CompoundTag restoreTag = new CompoundTag();
                                        restoreTag.putBoolean("EconomyBankNote", true);
                                        restoreTag.putDouble("Value", valueDouble);
                                        restored.set(DataComponents.CUSTOM_DATA, CustomData.of(restoreTag));
                                        restored.set(DataComponents.CUSTOM_NAME,
                                            TranslationHelper.translate("item.banknote.title", EconomyManager.getInstance().format(value)));
                                        if (!player.getInventory().add(restored)) {
                                            player.drop(restored, false);
                                        }
                                        player.sendSystemMessage(TranslationHelper.translate("listener.banknote.deposit_failed")
                                            .copy().withStyle(ChatFormatting.RED));
                                    }
                                });
                            });
                            
                            return InteractionResult.SUCCESS;
                        }
                    }
                }
            }
            return InteractionResult.PASS;
        });
    }
}
