package dev.invadvopt.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class PlayerIndex {
    private final IdentityHashMap<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>, CompiledPlan> plans = new IdentityHashMap<>();
    private final List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners = new ArrayList<>();
    private final Map<Integer, Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>>> byItem = new HashMap<>();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> alwaysCheck = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> wildcard = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> slotSensitive = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidateSet = IdentitySet.create();
    private final List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates = new ArrayList<>();
    private final Set<Integer> changedRawIds = new HashSet<>();
    private final InventorySnapshot snapshot = new InventorySnapshot();

    private long listenerGeneration;
    private long compiledListenerGeneration;
    private long registryGeneration = -1L;
    private long lastVerifiedTick = Long.MIN_VALUE;
    private boolean needsFullScan;
    private boolean disabled;
    private boolean forceVerification;

    public synchronized void add(CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener, long currentRegistryGeneration) {
        if (findEqual(listener) != null) {
            return;
        }
        listeners.add(listener);
        listenerGeneration++;
        // A newly registered criterion may need to match items that were already present
        // (login, revoke/reset, or an advancement reload), so its first event is full.
        snapshot.invalidate();
        CompiledPlan plan = PlanCompiler.compile(listener);
        plans.put(listener, plan);
        addToIndexes(plan);
        compiledListenerGeneration = listenerGeneration;
        registryGeneration = currentRegistryGeneration;
    }

    public synchronized void remove(CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> canonical = findEqual(listener);
        if (canonical == null) {
            needsFullScan = true;
            return;
        }
        CompiledPlan plan = plans.remove(canonical);
        listeners.remove(canonical);
        if (plan != null) {
            removeFromIndexes(plan);
        }
        listenerGeneration++;
        compiledListenerGeneration = listenerGeneration;
    }

    public synchronized Selection select(
            Inventory inventory,
            ItemStack changedStack,
            long currentRegistryGeneration,
            long currentTick,
            int periodicTicks) {
        boolean rebuilt = false;
        if (registryGeneration != currentRegistryGeneration || compiledListenerGeneration != listenerGeneration) {
            rebuild(currentRegistryGeneration);
            snapshot.invalidate();
            rebuilt = true;
        }

        changedRawIds.clear();
        boolean firstSnapshot = snapshot.updateAndCollectChanges(inventory, changedRawIds);
        if (!changedStack.isEmpty()) {
            int rawId = BuiltInRegistries.ITEM.getId(changedStack.getItem());
            if (rawId >= 0) {
                changedRawIds.add(rawId);
            }
        }

        boolean periodic = periodicTicks > 0
                && (lastVerifiedTick == Long.MIN_VALUE || currentTick - lastVerifiedTick >= periodicTicks);
        boolean verify = forceVerification || periodic;
        forceVerification = false;

        candidateSet.clear();
        candidates.clear();
        boolean mandatoryFull = firstSnapshot || rebuilt || needsFullScan;
        if (mandatoryFull) {
            addAll(listeners);
        } else {
            addAll(alwaysCheck);
            addAll(slotSensitive);
            addAll(wildcard);
            for (int rawId : changedRawIds) {
                Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> indexed = byItem.get(rawId);
                if (indexed != null) {
                    addAll(indexed);
                }
            }
        }
        return new Selection(candidates, listeners, mandatoryFull, verify, disabled, needsFullScan,
                listenerGeneration, registryGeneration);
    }

    public synchronized void verified(long tick) {
        lastVerifiedTick = tick;
    }

    public synchronized void markReload(long newRegistryGeneration) {
        registryGeneration = newRegistryGeneration - 1L;
        snapshot.invalidate();
        forceVerification = true;
    }

    public synchronized void requestVerification() {
        forceVerification = true;
    }

    public synchronized void disable() {
        disabled = true;
    }

    public synchronized void enable() {
        disabled = false;
        needsFullScan = false;
        forceVerification = true;
        snapshot.invalidate();
    }

    public synchronized boolean hasUnsafePlan() {
        return needsFullScan;
    }

    public synchronized int listenerCount() {
        return listeners.size();
    }

    private CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> findEqual(
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> existing : listeners) {
            if (existing.equals(listener)) {
                return existing;
            }
        }
        return null;
    }

    private void rebuild(long currentRegistryGeneration) {
        plans.clear();
        byItem.clear();
        alwaysCheck.clear();
        wildcard.clear();
        slotSensitive.clear();
        needsFullScan = false;
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : listeners) {
            CompiledPlan plan = PlanCompiler.compile(listener);
            plans.put(listener, plan);
            addToIndexes(plan);
        }
        compiledListenerGeneration = listenerGeneration;
        registryGeneration = currentRegistryGeneration;
    }

    private void addToIndexes(CompiledPlan plan) {
        if (plan.alwaysCheck()) alwaysCheck.add(plan.listener());
        if (plan.wildcard()) wildcard.add(plan.listener());
        if (plan.slotSensitive()) slotSensitive.add(plan.listener());
        if (!plan.indexSafe()) needsFullScan = true;
        for (int rawId : plan.rawItemIds()) {
            byItem.computeIfAbsent(rawId, ignored -> IdentitySet.create()).add(plan.listener());
        }
    }

    private void removeFromIndexes(CompiledPlan plan) {
        alwaysCheck.remove(plan.listener());
        wildcard.remove(plan.listener());
        slotSensitive.remove(plan.listener());
        for (int rawId : plan.rawItemIds()) {
            Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> indexed = byItem.get(rawId);
            if (indexed != null) {
                indexed.remove(plan.listener());
                if (indexed.isEmpty()) byItem.remove(rawId);
            }
        }
        if (!plan.indexSafe()) {
            needsFullScan = plans.values().stream().anyMatch(compiled -> !compiled.indexSafe());
        }
    }

    private void addAll(Iterable<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source) {
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : source) {
            if (plans.containsKey(listener) && candidateSet.add(listener)) {
                candidates.add(listener);
            }
        }
    }

    public record Selection(
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> allListeners,
            boolean mandatoryFull,
            boolean verify,
            boolean disabled,
            boolean unsafe,
            long listenerGeneration,
            long registryGeneration) {
    }
}
