package dev.invadvopt.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class PlayerIndex {
    private final PlanCompiler planCompiler;
    private final IdentityHashMap<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>, CompiledPlan> plans = new IdentityHashMap<>();
    private final Map<ListenerKey, CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> canonicalListeners = new HashMap<>();
    private final IdentityHashMap<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>, Integer> listenerPositions = new IdentityHashMap<>();
    private final List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners = new ArrayList<>();
    private final Map<Integer, Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>>> byItem = new HashMap<>();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> alwaysCheck = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> wildcard = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> slotSensitive = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> pendingCheck = IdentitySet.create();
    private final Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidateSet = IdentitySet.create();
    private final List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates = new ArrayList<>();
    private final InventorySnapshot snapshot = new InventorySnapshot();
    private final Selection selection = new Selection();

    private long listenerGeneration;
    private long compiledListenerGeneration;
    private long registryGeneration = -1L;
    private long lastVerifiedTick = Long.MIN_VALUE;
    private boolean disabled;
    private boolean forceVerification;

    public PlayerIndex() {
        this(new PlanCompiler());
    }

    public PlayerIndex(PlanCompiler planCompiler) {
        this.planCompiler = java.util.Objects.requireNonNull(planCompiler, "planCompiler");
    }

    public synchronized AddResult add(CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener, long currentRegistryGeneration) {
        if (registryGeneration != currentRegistryGeneration || compiledListenerGeneration != listenerGeneration) {
            rebuild(currentRegistryGeneration);
            snapshot.invalidate();
        }
        ListenerKey key = key(listener);
        if (canonicalListeners.containsKey(key)) {
            return AddResult.DUPLICATE;
        }
        canonicalListeners.put(key, listener);
        listenerPositions.put(listener, listeners.size());
        listeners.add(listener);
        listenerGeneration++;
        CompiledPlan plan = planCompiler.compile(listener, currentRegistryGeneration);
        plans.put(listener, plan);
        addToIndexes(plan);
        // A newly registered criterion may match an item which was already present. Only
        // that listener needs a first-event check; invalidating the player snapshot would
        // turn one registration into a complete scan of every advancement listener.
        pendingCheck.add(listener);
        compiledListenerGeneration = listenerGeneration;
        registryGeneration = currentRegistryGeneration;
        return plan.indexSafe() ? AddResult.ADDED : AddResult.ADDED_UNSAFE_PLAN;
    }

    public synchronized boolean remove(CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> canonical = canonicalListeners.remove(key(listener));
        if (canonical == null) {
            return false;
        }
        CompiledPlan plan = plans.remove(canonical);
        removeListenerPosition(canonical);
        pendingCheck.remove(canonical);
        if (plan != null) {
            removeFromIndexes(plan);
        }
        listenerGeneration++;
        compiledListenerGeneration = listenerGeneration;
        return true;
    }

    public synchronized int replaceAll(
            Iterable<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> currentListeners,
            long currentRegistryGeneration) {
        clearIndexState();
        int unsafePlans = 0;
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : currentListeners) {
            if (canonicalListeners.putIfAbsent(key(listener), listener) != null) continue;
            listenerPositions.put(listener, listeners.size());
            listeners.add(listener);
            CompiledPlan plan = planCompiler.compile(listener, currentRegistryGeneration);
            plans.put(listener, plan);
            addToIndexes(plan);
            if (!plan.indexSafe()) unsafePlans++;
        }
        listenerGeneration++;
        compiledListenerGeneration = listenerGeneration;
        registryGeneration = currentRegistryGeneration;
        snapshot.invalidate();
        forceVerification = true;
        return unsafePlans;
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

        boolean firstSnapshot = snapshot.update(inventory);
        snapshot.includeChangedStack(changedStack);

        boolean periodic = periodicTicks > 0
                && (lastVerifiedTick == Long.MIN_VALUE || currentTick - lastVerifiedTick >= periodicTicks);
        boolean verify = forceVerification || periodic;
        forceVerification = false;

        candidateSet.clear();
        candidates.clear();
        boolean mandatoryFull = firstSnapshot || rebuilt;
        if (mandatoryFull) {
            // Do not populate candidateSet here. Growing its IdentityHashMap to the full
            // listener count would make every later clear() scan that peak-sized table,
            // even when steady-state selection contains only a handful of listeners.
            candidates.addAll(listeners);
        } else {
            addAll(pendingCheck);
            addAll(alwaysCheck);
            addAll(slotSensitive);
            addAll(wildcard);
            for (int index = 0; index < snapshot.changedRawIdCount(); index++) {
                int rawId = snapshot.changedRawIdAt(index);
                Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> indexed = byItem.get(rawId);
                if (indexed != null) {
                    addAll(indexed);
                }
            }
        }
        pendingCheck.clear();
        return selection.update(candidates, listeners, candidateSet, mandatoryFull, verify,
                listenerGeneration, registryGeneration, snapshot.fullSlots(), snapshot.emptySlots(), snapshot.occupiedSlots());
    }

    public synchronized void verified(long tick) {
        lastVerifiedTick = tick;
    }

    public synchronized void requestVerification() {
        forceVerification = true;
    }

    public synchronized void disable() {
        disabled = true;
    }

    public synchronized String fallbackReason() {
        return disabled ? "index_desynchronized" : null;
    }

    public synchronized int listenerCount() {
        return listeners.size();
    }

    private void rebuild(long currentRegistryGeneration) {
        plans.clear();
        byItem.clear();
        alwaysCheck.clear();
        wildcard.clear();
        slotSensitive.clear();
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : listeners) {
            CompiledPlan plan = planCompiler.compile(listener, currentRegistryGeneration);
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
    }

    private void clearIndexState() {
        plans.clear();
        canonicalListeners.clear();
        listenerPositions.clear();
        listeners.clear();
        byItem.clear();
        alwaysCheck.clear();
        wildcard.clear();
        slotSensitive.clear();
        pendingCheck.clear();
        candidateSet.clear();
        candidates.clear();
    }

    private void removeListenerPosition(
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
        Integer position = listenerPositions.remove(listener);
        if (position == null) return;
        int lastPosition = listeners.size() - 1;
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> last = listeners.remove(lastPosition);
        if (position < lastPosition) {
            listeners.set(position, last);
            listenerPositions.put(last, position);
        }
    }

    private static ListenerKey key(
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
        return new ListenerKey(listener.advancement().id(), listener.criterion());
    }

    private void addAll(Iterable<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source) {
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : source) {
            if (candidateSet.add(listener)) {
                candidates.add(listener);
            }
        }
    }

    /**
     * Builds a new index without publishing partially populated state. The builder is intentionally
     * single-threaded; callers may advance it in bounded server-thread slices.
     */
    public static Builder builder(
            PlanCompiler planCompiler,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners,
            long registryGeneration) {
        return new Builder(planCompiler, List.copyOf(listeners), registryGeneration);
    }

    public static final class Builder {
        private final PlayerIndex index;
        private final List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source;
        private final long registryGeneration;
        private int position;
        private int unsafePlans;
        private int removedListeners;
        private boolean finished;

        private Builder(
                PlanCompiler planCompiler,
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source,
                long registryGeneration) {
            this.index = new PlayerIndex(planCompiler);
            this.source = source;
            this.registryGeneration = registryGeneration;
        }

        public boolean addNext() {
            if (finished || position >= source.size()) {
                return false;
            }
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener = source.get(position++);
            if (index.canonicalListeners.putIfAbsent(key(listener), listener) == null) {
                index.listenerPositions.put(listener, index.listeners.size());
                index.listeners.add(listener);
                CompiledPlan plan = index.planCompiler.compile(listener, registryGeneration);
                index.plans.put(listener, plan);
                index.addToIndexes(plan);
                if (!plan.indexSafe()) unsafePlans++;
            }
            return true;
        }

        public boolean complete() {
            return position >= source.size();
        }

        public int sourceSize() {
            return source.size();
        }

        public int unsafePlans() {
            return unsafePlans;
        }

        public int removedListeners() {
            return removedListeners;
        }

        /**
         * Revalidates a completed, unpublished build against the current vanilla set.
         * Removals cannot introduce a new plan, so they can be pruned without compiling
         * surviving listeners again. Additions, replacements and duplicate logical keys
         * fail validation before any pruning. The caller must retry from a fresh source.
         */
        public boolean retainAuthoritative(
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative) {
            if (!complete() || finished) {
                throw new IllegalStateException("Only a complete unpublished build can be reconciled");
            }
            Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> retained = IdentitySet.create();
            for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : authoritative) {
                CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> expected =
                        index.canonicalListeners.get(key(listener));
                if (expected == null || expected.advancement() != listener.advancement()
                        || expected.trigger() != listener.trigger() || !retained.add(expected)) {
                    return false;
                }
            }

            // Walk backwards because removal uses a swap-with-last dense listener store.
            for (int position = index.listeners.size() - 1; position >= 0; position--) {
                CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener = index.listeners.get(position);
                if (!retained.contains(listener)) {
                    CompiledPlan plan = index.plans.get(listener);
                    if (plan != null && !plan.indexSafe()) unsafePlans--;
                    index.remove(listener);
                    removedListeners++;
                }
            }
            return true;
        }

        public boolean matches(
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative) {
            if (authoritative.size() != index.canonicalListeners.size()) return false;
            for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : authoritative) {
                CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> expected =
                        index.canonicalListeners.get(key(listener));
                if (expected == null
                        || expected.advancement() != listener.advancement()
                        || expected.trigger() != listener.trigger()) {
                    return false;
                }
            }
            return true;
        }

        public PlayerIndex finish() {
            if (!complete()) {
                throw new IllegalStateException("Cannot publish a partially built player index");
            }
            if (!finished) {
                index.listenerGeneration++;
                index.compiledListenerGeneration = index.listenerGeneration;
                index.registryGeneration = registryGeneration;
                index.snapshot.invalidate();
                index.forceVerification = true;
                finished = true;
            }
            return index;
        }
    }

    public static final class Selection {
        private List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates;
        private List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> allListeners;
        private Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidateSet;
        private boolean mandatoryFull;
        private boolean verify;
        private long listenerGeneration;
        private long registryGeneration;
        private int fullSlots;
        private int emptySlots;
        private int occupiedSlots;

        private Selection update(
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates,
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> allListeners,
                Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidateSet,
                boolean mandatoryFull,
                boolean verify,
                long listenerGeneration,
                long registryGeneration,
                int fullSlots,
                int emptySlots,
                int occupiedSlots) {
            this.candidates = candidates;
            this.allListeners = allListeners;
            this.candidateSet = candidateSet;
            this.mandatoryFull = mandatoryFull;
            this.verify = verify;
            this.listenerGeneration = listenerGeneration;
            this.registryGeneration = registryGeneration;
            this.fullSlots = fullSlots;
            this.emptySlots = emptySlots;
            this.occupiedSlots = occupiedSlots;
            return this;
        }

        public List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates() { return candidates; }
        public List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> allListeners() { return allListeners; }
        public boolean mandatoryFull() { return mandatoryFull; }
        public boolean verify() { return verify; }
        public long listenerGeneration() { return listenerGeneration; }
        public long registryGeneration() { return registryGeneration; }
        public int fullSlots() { return fullSlots; }
        public int emptySlots() { return emptySlots; }
        public int occupiedSlots() { return occupiedSlots; }

        public boolean isCandidate(CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener) {
            return mandatoryFull || candidateSet.contains(listener);
        }
    }

    public enum AddResult {
        ADDED,
        ADDED_UNSAFE_PLAN,
        DUPLICATE
    }

    private record ListenerKey(ResourceLocation advancementId, String criterion) {}
}
