package savage.commoneconomy.shopv2;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/**
 * Reads and changes a player's inventory for shops (D6): only the 36 main slots and the hotbar, never armor or the
 * offhand. Matching is strict, the same item and the same components. Call it from the server thread only.
 */
final class PlayerItems {

    private static final int MAIN_SLOTS = 36;

    private PlayerItems() {}

    /**
     * @return how many of the item the player carries
     */
    static int count(ServerPlayer player, ItemStack template) {
        int count = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Takes the amount of the item from the player.
     *
     * @return false, with the inventory untouched, if the player carries less than the amount
     */
    static boolean remove(ServerPlayer player, ItemStack template, int amount) {
        if (amount <= 0 || count(player, template) < amount) {
            return false;
        }
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                int take = Math.min(remaining, stack.getCount());
                player.getInventory().removeItem(slot, take);
                remaining -= take;
            }
        }
        return true;
    }

    /**
     * Gives the player the amount of the item. The room was checked before the payment, so the inventory can fill
     * up in between; whatever does not fit is dropped at the player's feet in normal-sized stacks, and never lost.
     */
    static void give(ServerPlayer player, ItemStack template, int amount) {
        ItemStack stack = template.copyWithCount(amount);
        if (player.getInventory().add(stack)) {
            return;
        }
        // Inventory.add leaves the part that did not fit in the stack
        while (!stack.isEmpty()) {
            player.drop(stack.split(stack.getMaxStackSize()), false, Prediction.SERVER_ONLY);
        }
    }
}
