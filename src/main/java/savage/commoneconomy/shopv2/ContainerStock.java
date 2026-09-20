package savage.commoneconomy.shopv2;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Counts how much of an item a container holds and how much more it would take (D6).
 * Matching is strict: the same item and the same components. Room follows the container's own rules,
 * its stack limit and what each slot accepts, instead of assuming every slot takes a full stack.
 */
public final class ContainerStock {

    private ContainerStock() {}

    /**
     * @return how many of the item are in the container
     */
    public static int count(Container container, ItemStack template) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * @return how many more of the item the container could take
     */
    public static int space(Container container, ItemStack template) {
        int limit = container.getMaxStackSize(template);
        int space = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            space += roomInSlot(container, slot, template, limit);
        }
        return space;
    }

    /**
     * How many more of the item one slot takes: nothing if the slot does not accept it or holds something else,
     * a full stack if it is empty, and what is left under the limit if it holds the same item. Both {@link #space}
     * and {@link ContainerMoves#insert} use this, so what is counted as room is exactly what gets filled.
     *
     * @param limit the container's stack limit for this item
     */
    static int roomInSlot(Container container, int slot, ItemStack template, int limit) {
        if (!container.canPlaceItem(slot, template)) {
            return 0;
        }
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
            return limit;
        }
        return ItemStack.isSameItemSameComponents(stack, template) ? Math.max(0, limit - stack.getCount()) : 0;
    }
}
