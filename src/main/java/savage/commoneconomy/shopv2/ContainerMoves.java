package savage.commoneconomy.shopv2;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Puts items into a container and takes them out (D6). Each move is all-or-nothing: it is worked out first, and the
 * container is only touched if the whole amount can move. Room follows the container's own rules, its stack limit
 * and what each slot accepts, instead of writing slots directly as v1 does. Call it from the server thread only.
 */
final class ContainerMoves {

    private ContainerMoves() {}

    /**
     * Adds the amount of the item, filling stacks that already hold it before using empty slots.
     *
     * @return false, with the container untouched, if the whole amount does not fit
     */
    static boolean insert(Container container, ItemStack template, int amount) {
        if (amount <= 0) {
            return false;
        }
        int limit = container.getMaxStackSize(template);
        int size = container.getContainerSize();
        int[] toAdd = new int[size];
        int remaining = amount;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            boolean emptySlots = pass == 1;
            for (int slot = 0; slot < size && remaining > 0; slot++) {
                if (container.getItem(slot).isEmpty() != emptySlots) {
                    continue;
                }
                toAdd[slot] = Math.min(remaining, ContainerStock.roomInSlot(container, slot, template, limit));
                remaining -= toAdd[slot];
            }
        }
        if (remaining > 0) {
            return false;
        }

        for (int slot = 0; slot < size; slot++) {
            if (toAdd[slot] == 0) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                container.setItem(slot, template.copyWithCount(toAdd[slot]));
            } else {
                existing.grow(toAdd[slot]);
            }
        }
        container.setChanged();
        return true;
    }

    /**
     * Takes the amount of the item out of the container.
     *
     * @return false, with the container untouched, if it holds less than the amount
     */
    static boolean remove(Container container, ItemStack template, int amount) {
        if (amount <= 0 || ContainerStock.count(container, template) < amount) {
            return false;
        }
        int remaining = amount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                int take = Math.min(remaining, stack.getCount());
                container.removeItem(slot, take);
                remaining -= take;
            }
        }
        container.setChanged();
        return true;
    }
}
