package savage.commoneconomy.core.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class InventorySpace {

    private InventorySpace() {}

    /**
     * How many items matching the template fit in the player's main inventory and hotbar
     * (the 36 slots Inventory.add fills), counting empty slots and partially filled matching stacks.
     */
    public static int getAvailableSpace(ServerPlayer player, ItemStack template) {
        int space = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                space += template.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, template)) {
                space += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return space;
    }
}
