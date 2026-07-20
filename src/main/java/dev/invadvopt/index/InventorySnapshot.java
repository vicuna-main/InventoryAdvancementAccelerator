package dev.invadvopt.index;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class InventorySnapshot {
    private final ItemStack[] main = new ItemStack[Inventory.INVENTORY_SIZE];
    private boolean initialized;

    public boolean updateAndCollectChanges(Inventory inventory, Set<Integer> changedRawIds) {
        boolean first = !initialized;
        for (int slot = 0; slot < main.length; slot++) {
            ItemStack current = inventory.getItem(slot);
            ItemStack previous = main[slot];
            if (first || previous == null || !ItemStack.matches(previous, current)) {
                addRawId(previous, changedRawIds);
                addRawId(current, changedRawIds);
                main[slot] = current.isEmpty() ? ItemStack.EMPTY : current.copy();
            }
        }
        initialized = true;
        return first;
    }

    public void invalidate() {
        initialized = false;
    }

    private static void addRawId(ItemStack stack, Set<Integer> target) {
        if (stack != null && !stack.isEmpty()) {
            int rawId = BuiltInRegistries.ITEM.getId(stack.getItem());
            if (rawId >= 0) {
                target.add(rawId);
            }
        }
    }
}
