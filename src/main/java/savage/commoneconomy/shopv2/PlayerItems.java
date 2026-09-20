package savage.commoneconomy.shopv2;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Reads a player's inventory for shops (D6): only the 36 main slots and the hotbar, never armor or the offhand.
 * Matching is strict, the same item and the same components.
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
}
