package savage.commoneconomy.banknote;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;

import java.math.BigDecimal;
import java.util.OptionalDouble;

/**
 * Owns the bank note item format: paper carrying custom data
 * {EconomyBankNote: true, Value: <double>}. Redemption reads this exact format,
 * so notes already in players' inventories depend on it staying the same.
 */
public final class BankNote {
    private static final String TAG_MARKER = "EconomyBankNote";
    private static final String TAG_VALUE = "Value";

    private BankNote() {}

    public static ItemStack create(double value) {
        ItemStack note = new ItemStack(Items.PAPER);

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_MARKER, true);
        tag.putDouble(TAG_VALUE, value);
        note.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        note.set(DataComponents.CUSTOM_NAME,
            TranslationHelper.translate("item.banknote.title", EconomyService.get().format(BigDecimal.valueOf(value))));
        return note;
    }

    /**
     * @return the note's value, or empty if the stack is not a bank note.
     */
    public static OptionalDouble readValue(ItemStack stack) {
        if (!stack.is(Items.PAPER)) return OptionalDouble.empty();

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return OptionalDouble.empty();

        CompoundTag tag = customData.copyTag();
        if (!tag.contains(TAG_MARKER) || !tag.contains(TAG_VALUE)) return OptionalDouble.empty();

        return OptionalDouble.of(tag.getDouble(TAG_VALUE).orElse(0.0));
    }
}
