package dev.invadvopt.index;

import java.util.Arrays;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class InventorySnapshot {
    private ItemStack[] slots = new ItemStack[0];
    private int[] changedRawIds = new int[1];
    private int changedRawIdCount;
    private int fullSlots;
    private int emptySlots;
    private int occupiedSlots;
    private boolean initialized;

    public boolean updateAndCollectChanges(Inventory inventory, Set<Integer> changedRawIds) {
        boolean first = update(inventory);
        for (int index = 0; index < changedRawIdCount; index++) {
            changedRawIds.add(this.changedRawIds[index]);
        }
        return first;
    }

    boolean update(Inventory inventory) {
        boolean first = !initialized;
        changedRawIdCount = 0;
        fullSlots = 0;
        emptySlots = 0;
        occupiedSlots = 0;

        int containerSize = inventory.getContainerSize();
        ItemStack[] previousSlots = slots;
        if (containerSize != slots.length) {
            // The vanilla matcher scans getContainerSize(), including armor/offhand and
            // any extra slots supplied by a compatible Inventory implementation.
            slots = Arrays.copyOf(slots, containerSize);
        }
        int requiredCapacity = Math.addExact(Math.multiplyExact(Math.max(containerSize, previousSlots.length), 2), 1);
        if (changedRawIds.length < requiredCapacity) {
            changedRawIds = new int[requiredCapacity];
        }
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty()) {
                emptySlots++;
            } else {
                occupiedSlots++;
                if (current.getCount() >= current.getMaxStackSize()) fullSlots++;
            }

            ItemStack previous = slots[slot];
            if (first || previous == null || !ItemStack.matches(previous, current)) {
                if (!first) addRawId(previous);
                if (!first) addRawId(current);
                slots[slot] = current.isEmpty() ? ItemStack.EMPTY : current.copy();
            }
        }
        for (int slot = containerSize; slot < previousSlots.length; slot++) {
            ItemStack previous = previousSlots[slot];
            if (!first && previous != null && !previous.isEmpty()) addRawId(previous);
        }
        initialized = true;
        return first;
    }

    void includeChangedStack(ItemStack stack) {
        addRawId(stack);
    }

    int changedRawIdCount() {
        return changedRawIdCount;
    }

    int changedRawIdAt(int index) {
        return changedRawIds[index];
    }

    int fullSlots() {
        return fullSlots;
    }

    int emptySlots() {
        return emptySlots;
    }

    int occupiedSlots() {
        return occupiedSlots;
    }

    public void invalidate() {
        initialized = false;
    }

    private void addRawId(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            int rawId = BuiltInRegistries.ITEM.getId(stack.getItem());
            if (rawId < 0) return;
            for (int index = 0; index < changedRawIdCount; index++) {
                if (changedRawIds[index] == rawId) return;
            }
            if (changedRawIdCount >= changedRawIds.length) {
                throw new IllegalStateException("Inventory change set exceeded its slot-derived safety bound");
            }
            changedRawIds[changedRawIdCount++] = rawId;
        }
    }
}
