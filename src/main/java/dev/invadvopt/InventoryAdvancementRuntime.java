package dev.invadvopt;

import com.mojang.logging.LogUtils;
import dev.invadvopt.config.InvAdvOptConfig;
import dev.invadvopt.index.PlanCompiler;
import dev.invadvopt.index.PlayerIndex;
import dev.invadvopt.metrics.StatsCollector;
import dev.invadvopt.mixin.SimpleCriterionTriggerAccessor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

public final class InventoryAdvancementRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> CONFLICTING_MODS = Set.of("achiopt", "cerulean", "icterine");
    private static final int GLOBAL_MISMATCH_THRESHOLD = 3;
    private static final int MAX_PENDING_WARMUPS = 1024;
    private static final Verification MATCHED_VERIFICATION =
            new Verification(true, null, List.of(), List.of());

    private final Map<PlayerAdvancements, PlayerIndex> indexes = new IdentityHashMap<>();
    private final Deque<PlayerAdvancements> pendingWarmups = new ArrayDeque<>();
    private final Set<PlayerAdvancements> pendingWarmupSet =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final PlanCompiler planCompiler = new PlanCompiler();
    private final AtomicLong registryGeneration = new AtomicLong();
    private final AtomicInteger activePredicateScopes = new AtomicInteger();
    private final StatsCollector stats = new StatsCollector();
    private final ThreadLocal<ProcessingState> processing = ThreadLocal.withInitial(ProcessingState::new);

    private volatile OptimizationMode commandMode;
    private volatile boolean reloadInProgress;
    private volatile boolean replacementHealthy;
    private volatile boolean selfCheckCompleted;
    private volatile boolean indexMaintenanceSuspended;
    private volatile boolean listenerAccessFailureLogged;
    private volatile String disabledReason = "server_not_started";
    private int consecutiveMismatches;
    private WarmupTask activeWarmup;

    public void startupSelfCheck() {
        stats.setEnabled(InvAdvOptConfig.METRICS_ENABLED.get());
        List<String> conflicts = CONFLICTING_MODS.stream().filter(id -> ModList.get().isLoaded(id)).toList();
        if (!conflicts.isEmpty() || Boolean.getBoolean("invadvopt.mixin.conflict")) {
            replacementHealthy = false;
            selfCheckCompleted = true;
            disabledReason = "conflicting_mod:" + String.join(",", conflicts);
            suspendIndexMaintenance();
            LOGGER.error("[invadvopt] Trigger replacement refused because a conflicting advancement optimizer is loaded: {}. Vanilla behavior is retained.", conflicts);
            return;
        }

        List<String> missingHooks = new ArrayList<>();
        for (String hook : List.of("listener", "trigger", "predicate", "reload")) {
            if (!Boolean.getBoolean("invadvopt.mixin." + hook)) missingHooks.add(hook);
        }
        if (!verifyVanillaDescriptors()) missingHooks.add("vanilla_descriptors");
        if (!((Object)CriteriaTriggers.INVENTORY_CHANGED instanceof SimpleCriterionTriggerAccessor)) {
            missingHooks.add("listener_accessor");
        }
        if (!missingHooks.isEmpty()) {
            replacementHealthy = false;
            selfCheckCompleted = true;
            disabledReason = "mixin_self_check:" + String.join(",", missingHooks);
            suspendIndexMaintenance();
            LOGGER.error("[invadvopt] Mixin self-check failed ({}). Optimization is disabled; the original trigger remains active.", missingHooks);
            return;
        }
        replacementHealthy = true;
        selfCheckCompleted = true;
        disabledReason = "none";
        if (!Boolean.getBoolean("invadvopt.mixin.bulk")) {
            LOGGER.warn("[invadvopt] Bulk-registration warmup hook is unavailable; indexes will warm safely after the first trigger.");
        }
        LOGGER.info("[invadvopt] EXACT inventory advancement acceleration is ready (registry generation {}).", registryGeneration.get());
    }

    private boolean verifyVanillaDescriptors() {
        try {
            Method trigger = InventoryChangeTrigger.class.getDeclaredMethod("trigger", ServerPlayer.class, Inventory.class, ItemStack.class);
            Method add = SimpleCriterionTrigger.class.getDeclaredMethod("addPlayerListener", PlayerAdvancements.class, CriterionTrigger.Listener.class);
            Method remove = SimpleCriterionTrigger.class.getDeclaredMethod("removePlayerListener", PlayerAdvancements.class, CriterionTrigger.Listener.class);
            Method removeAll = SimpleCriterionTrigger.class.getDeclaredMethod("removePlayerListeners", PlayerAdvancements.class);
            return trigger.getReturnType() == void.class && add.getReturnType() == void.class
                    && remove.getReturnType() == void.class && removeAll.getReturnType() == void.class;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.error("[invadvopt] Vanilla method descriptor verification failed.", exception);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public void addListener(InventoryChangeTrigger trigger, PlayerAdvancements advancements, CriterionTrigger.Listener<?> listener) {
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return;
        }
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> typed =
                (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>) listener;
        synchronized (indexes) {
            resumeIndexMaintenance();
            invalidateActiveWarmup(advancements, true);
            PlayerIndex index = indexes.get(advancements);
            if (index == null) return;
            PlayerIndex.AddResult result = index.add(typed, registryGeneration.get());
            if (result == PlayerIndex.AddResult.ADDED_UNSAFE_PLAN) {
                stats.recordIndexCondition("unsafe_plan");
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void removeListener(InventoryChangeTrigger trigger, PlayerAdvancements advancements, CriterionTrigger.Listener<?> listener) {
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return;
        }
        synchronized (indexes) {
            resumeIndexMaintenance();
            invalidateActiveWarmup(advancements, true);
            PlayerIndex index = indexes.get(advancements);
            if (index == null) return;
            if (!index.remove((CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>) listener)) {
                stats.recordIndexCondition("remove_miss");
            }
        }
    }

    public void removeListeners(PlayerAdvancements advancements) {
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return;
        }
        synchronized (indexes) {
            indexes.remove(advancements);
            cancelWarmup(advancements);
        }
    }

    /** Called after vanilla has completed a full listener registration pass. */
    public void listenersRegistered(PlayerAdvancements advancements) {
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return;
        }
        synchronized (indexes) {
            resumeIndexMaintenance();
            indexes.remove(advancements);
            cancelWarmup(advancements);
            queueWarmup(advancements);
        }
    }

    public boolean handleTrigger(InventoryChangeTrigger trigger, ServerPlayer player, Inventory inventory, ItemStack changedStack) {
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return false;
        }

        ProcessingState state = processing.get();
        if (state.optimizing) {
            beginVanilla(state, player, changedStack, listenerCount(player), "reentrant_call");
            return false;
        }

        String unavailable = unavailableReason(player);
        if (unavailable != null) {
            beginVanilla(state, player, changedStack, listenerCount(player), unavailable);
            return false;
        }

        PlayerIndex index;
        synchronized (indexes) {
            resumeIndexMaintenance();
            index = indexes.get(player.getAdvancements());
            if (index == null) {
                queueWarmup(player.getAdvancements());
            }
        }
        if (index == null) {
            beginVanilla(state, player, changedStack, 0, "index_warming");
            return false;
        }

        int rawCount = index.listenerCount();
        String indexFallback = index.fallbackReason();
        if (indexFallback != null) {
            beginVanilla(state, player, changedStack, rawCount, indexFallback);
            return false;
        }

        state.optimizing = true;
        long started = stats.enabled() ? System.nanoTime() : 0L;
        startPredicateScope(state);
        int candidateCount = rawCount;
        try {
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> awards;
            long tick = player.getServer().getTickCount();
            synchronized (index) {
                PlayerIndex.Selection selection = index.select(inventory, changedStack, registryGeneration.get(), tick,
                        InvAdvOptConfig.PERIODIC_FULL_SCAN_TICKS.get());
                rawCount = selection.allListeners().size();
                candidateCount = selection.candidates().size();
                double shadowVerifyRate = InvAdvOptConfig.SHADOW_VERIFY_RATE.get();
                boolean sampleVerify = selection.verify() || shadowVerifyRate > 0.0D
                        && ThreadLocalRandom.current().nextDouble() < shadowVerifyRate;
                if (selection.mandatoryFull()) stats.recordFullScan();
                state.matchContext.reset(player);
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> optimizedMatches =
                        evaluate(selection.candidates(), inventory, changedStack, selection.fullSlots(), selection.emptySlots(),
                                selection.occupiedSlots(), state.matchContext);
                awards = optimizedMatches;

                if (sampleVerify && !selection.mandatoryFull()) {
                    stats.recordFullScan();
                    Verification verification = verifyFull(selection, optimizedMatches, inventory, changedStack,
                            selection.fullSlots(), selection.emptySlots(), selection.occupiedSlots(), state.matchContext);
                    index.verified(tick);
                    if (!verification.matches()) {
                        awards = verification.fullMatches();
                        onMismatch(index, player, changedStack, selection, verification);
                    } else {
                        consecutiveMismatches = 0;
                    }
                } else if (sampleVerify) {
                    index.verified(tick);
                }
            }

            if (awards != null) {
                for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : awards) {
                    listener.run(player.getAdvancements());
                }
            }
            stats.recordTrigger(player, changedStack, rawCount, candidateCount,
                    started == 0L ? 0L : System.nanoTime() - started);
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("[invadvopt] Optimized trigger failed safely for player {} and item {}; retrying through vanilla.",
                    player.getUUID(), safeItemName(changedStack), throwable);
            return fallThroughFromOptimized(state, player, changedStack, rawCount, "optimization_exception", started);
        } finally {
            state.matchContext.clear();
            if (state.optimizing) {
                endPredicateScope(state);
                state.optimizing = false;
            }
        }
    }

    private List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> evaluate(
            Iterable<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source,
            Inventory inventory, ItemStack changedStack, int full, int empty, int occupied, LazyMatchContext context) {
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> result = null;
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : source) {
            if (matches(listener, inventory, changedStack, full, empty, occupied, context)) {
                if (result == null) result = new ArrayList<>();
                result.add(listener);
            }
        }
        return result;
    }

    private Verification verifyFull(
            PlayerIndex.Selection selection,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> optimizedMatches,
            Inventory inventory, ItemStack changedStack, int full, int empty, int occupied, LazyMatchContext context) {
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> missing = null;
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : selection.allListeners()) {
            if (selection.isCandidate(listener)) continue;
            if (matches(listener, inventory, changedStack, full, empty, occupied, context)) {
                if (missing == null) missing = new ArrayList<>();
                missing.add(listener);
            }
        }
        if (missing == null) return MATCHED_VERIFICATION;

        // Candidate listeners were matched by the original TriggerInstance above, so the
        // complete result is exactly optimizedMatches plus any matching omitted listener.
        // The former set-based implementation built four identity maps to derive the same
        // result and could never produce an "extra" entry because it seeded the full set
        // with every optimized match.
        int optimizedSize = optimizedMatches == null ? 0 : optimizedMatches.size();
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> fullMatches =
                new ArrayList<>(optimizedSize + missing.size());
        if (optimizedMatches != null) fullMatches.addAll(optimizedMatches);
        fullMatches.addAll(missing);
        return new Verification(false, fullMatches, missing, List.of());
    }

    private static boolean matches(
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener,
            Inventory inventory, ItemStack changedStack, int full, int empty, int occupied, LazyMatchContext context) {
        InventoryChangeTrigger.TriggerInstance trigger = listener.trigger();
        if (!trigger.matches(inventory, changedStack, full, empty, occupied)) return false;
        Optional<ContextAwarePredicate> playerPredicate = trigger.player();
        return playerPredicate.isEmpty() || playerPredicate.get().matches(context.get());
    }

    private void onMismatch(PlayerIndex index, ServerPlayer player, ItemStack stack, PlayerIndex.Selection selection, Verification result) {
        stats.recordMismatch();
        consecutiveMismatches++;
        if (InvAdvOptConfig.DISABLE_ON_MISMATCH.get()) index.disable();
        logMismatch("missing", result.missing(), player, stack, selection);
        logMismatch("extra", result.extra(), player, stack, selection);
        if (consecutiveMismatches >= GLOBAL_MISMATCH_THRESHOLD) {
            commandMode = OptimizationMode.VANILLA;
            disabledReason = "global_mismatch_circuit_breaker";
            LOGGER.error("[invadvopt] {} consecutive verification mismatches; globally switching to VANILLA mode.", consecutiveMismatches);
        }
    }

    private void logMismatch(String type, Iterable<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners,
            ServerPlayer player, ItemStack stack, PlayerIndex.Selection selection) {
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : listeners) {
            LOGGER.error("[invadvopt] Shadow mismatch type={} advancement={} criterion={} player={} item={} listenerGeneration={} registryGeneration={}",
                    type, listener.advancement().id(), listener.criterion(), player.getUUID(), safeItemName(stack),
                    selection.listenerGeneration(), selection.registryGeneration());
        }
    }

    private String unavailableReason(ServerPlayer player) {
        if (reloadInProgress) return "datapack_reload_in_progress";
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) return "off_thread_call";
        return null;
    }

    private boolean shouldMaintainIndexes() {
        return InvAdvOptConfig.ENABLED.get()
                && mode() != OptimizationMode.VANILLA
                && (!selfCheckCompleted || replacementHealthy);
    }

    private void suspendIndexMaintenance() {
        if (indexMaintenanceSuspended) return;
        synchronized (indexes) {
            if (indexMaintenanceSuspended) return;
            indexes.clear();
            pendingWarmups.clear();
            pendingWarmupSet.clear();
            activeWarmup = null;
            planCompiler.clear();
            indexMaintenanceSuspended = true;
        }
    }

    /** Must be called while holding {@link #indexes}. */
    private void resumeIndexMaintenance() {
        if (!indexMaintenanceSuspended) return;
        indexes.clear();
        indexMaintenanceSuspended = false;
    }

    public void processIndexWarmups(MinecraftServer server) {
        if (server == null || !server.isSameThread() || reloadInProgress) return;
        if (!shouldMaintainIndexes()) {
            suspendIndexMaintenance();
            return;
        }
        long budgetNanos = InvAdvOptConfig.INDEX_WARMUP_BUDGET_MICROS.get() * 1_000L;
        long deadline = System.nanoTime() + budgetNanos;
        synchronized (indexes) {
            resumeIndexMaintenance();
            try {
                do {
                    if (activeWarmup == null) {
                        PlayerAdvancements advancements = pollWarmup();
                        if (advancements == null) return;
                        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners =
                                currentListeners(CriteriaTriggers.INVENTORY_CHANGED, advancements);
                        long generation = registryGeneration.get();
                        activeWarmup = new WarmupTask(
                                advancements,
                                PlayerIndex.builder(planCompiler, listeners, generation),
                                generation);
                    }

                    int compiled = 0;
                    while (compiled < 16 && activeWarmup.builder().addNext()) compiled++;
                    if (activeWarmup.builder().complete()) {
                        publishWarmup(activeWarmup);
                        activeWarmup = null;
                    }
                } while (System.nanoTime() < deadline);
            } catch (RuntimeException | LinkageError exception) {
                PlayerAdvancements failed = activeWarmup == null ? null : activeWarmup.advancements();
                activeWarmup = null;
                if (!listenerAccessFailureLogged) {
                    listenerAccessFailureLogged = true;
                    LOGGER.error("[invadvopt] Could not warm the inventory advancement listener index; vanilla behavior is retained.", exception);
                }
                if (failed != null) indexes.remove(failed);
            }
        }
    }

    private void publishWarmup(WarmupTask task) {
        if (task.registryGeneration() != registryGeneration.get()) {
            requeueWarmup(task.advancements());
            return;
        }
        try {
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative =
                    currentListeners(CriteriaTriggers.INVENTORY_CHANGED, task.advancements());
            if (!sameListeners(authoritative, task.builder())) {
                requeueWarmup(task.advancements());
                return;
            }
            PlayerIndex index = task.builder().finish();
            stats.recordIndexCondition("unsafe_plan", task.builder().unsafePlans());
            stats.recordIndexCondition("warmup_completed");
            stats.recordIndexCondition("warmup_listeners", task.builder().sourceSize());
            indexes.put(task.advancements(), index);
            listenerAccessFailureLogged = false;
        } catch (RuntimeException | LinkageError exception) {
            if (!listenerAccessFailureLogged) {
                listenerAccessFailureLogged = true;
                LOGGER.error("[invadvopt] Could not publish the inventory advancement listener index; vanilla behavior is retained.", exception);
            }
            indexes.remove(task.advancements());
        }
    }

    private static boolean sameListeners(
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative,
            PlayerIndex.Builder builder) {
        // The vanilla set is authoritative. A generation-free final equality check prevents a
        // bypassing mixin from causing a stale private build to become visible.
        return builder.matches(authoritative);
    }

    private void queueWarmup(PlayerAdvancements advancements) {
        if (indexes.containsKey(advancements)) return;
        if (activeWarmup != null && activeWarmup.advancements() == advancements) return;
        if (pendingWarmupSet.size() >= MAX_PENDING_WARMUPS) return;
        if (pendingWarmupSet.add(advancements)) pendingWarmups.addLast(advancements);
    }

    private void requeueWarmup(PlayerAdvancements advancements) {
        if (pendingWarmupSet.size() >= MAX_PENDING_WARMUPS) return;
        if (pendingWarmupSet.add(advancements)) pendingWarmups.addLast(advancements);
    }

    private PlayerAdvancements pollWarmup() {
        while (!pendingWarmups.isEmpty()) {
            PlayerAdvancements advancements = pendingWarmups.removeFirst();
            if (pendingWarmupSet.remove(advancements)) return advancements;
        }
        return null;
    }

    private void cancelWarmup(PlayerAdvancements advancements) {
        pendingWarmupSet.remove(advancements);
        if (activeWarmup != null && activeWarmup.advancements() == advancements) activeWarmup = null;
    }

    private void invalidateActiveWarmup(PlayerAdvancements advancements, boolean requeue) {
        if (activeWarmup != null && activeWarmup.advancements() == advancements) {
            activeWarmup = null;
            if (requeue) queueWarmup(advancements);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> currentListeners(
            InventoryChangeTrigger trigger, PlayerAdvancements advancements) {
        Set<CriterionTrigger.Listener<?>> source = ((SimpleCriterionTriggerAccessor)(Object)trigger)
                .invadvopt$getPlayers().get(advancements);
        if (source == null || source.isEmpty()) return List.of();
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> result = new ArrayList<>(source.size());
        for (CriterionTrigger.Listener<?> listener : source) {
            result.add((CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>)listener);
        }
        return result;
    }

    private boolean fallThroughFromOptimized(ProcessingState state, ServerPlayer player, ItemStack stack,
            int rawCount, String reason, long started) {
        endPredicateScope(state);
        state.optimizing = false;
        beginVanilla(state, player, stack, rawCount, reason, started);
        return false;
    }

    private void beginVanilla(ProcessingState state, ServerPlayer player, ItemStack stack, int rawCount, String reason) {
        beginVanilla(state, player, stack, rawCount, reason, stats.enabled() ? System.nanoTime() : 0L);
    }

    private void beginVanilla(ProcessingState state, ServerPlayer player, ItemStack stack, int rawCount, String reason, long started) {
        stats.recordFallback(reason);
        boolean trackPredicates = !"index_desynchronized".equals(reason);
        if (trackPredicates) startPredicateScope(state);
        state.vanillaFrames.push(new VanillaFrame(player, stack, rawCount, reason, started, trackPredicates));
    }

    public void onVanillaTriggerReturn() {
        ProcessingState state = processing.get();
        VanillaFrame frame = state.vanillaFrames.poll();
        if (frame == null) return;
        if (frame.trackPredicates()) endPredicateScope(state);
        stats.recordTrigger(frame.player(), frame.stack(), frame.rawListeners(), frame.rawListeners(),
                frame.started() == 0L ? 0L : System.nanoTime() - frame.started());
        if (state.vanillaFrames.isEmpty() && !state.optimizing) processing.remove();
    }

    public void onItemPredicateTest() {
        if (activePredicateScopes.get() <= 0) return;
        ProcessingState state = processing.get();
        if (state.predicateScopeDepth > 0) {
            state.predicateTests++;
        } else {
            processing.remove();
        }
    }

    private void startPredicateScope(ProcessingState state) {
        if (!stats.enabled()) return;
        if (state.predicateScopeDepth++ == 0) {
            state.predicateTests = 0L;
            activePredicateScopes.incrementAndGet();
        }
    }

    private void endPredicateScope(ProcessingState state) {
        if (state.predicateScopeDepth <= 0) return;
        if (--state.predicateScopeDepth == 0) {
            stats.recordPredicateTests(state.predicateTests);
            state.predicateTests = 0L;
            if (activePredicateScopes.decrementAndGet() < 0) activePredicateScopes.set(0);
        }
    }

    public void reloadStarted() {
        reloadInProgress = true;
    }

    public void reloadFuture(CompletableFuture<Void> future) {
        if (future == null) {
            reloadCompleted();
            return;
        }
        future.whenComplete((ignored, throwable) -> reloadCompleted());
    }

    public void tagsUpdated() {
        reloadCompleted();
    }

    private void reloadCompleted() {
        registryGeneration.incrementAndGet();
        if (shouldMaintainIndexes()) {
            synchronized (indexes) {
                Set<PlayerAdvancements> rebuild = Collections.newSetFromMap(new IdentityHashMap<>());
                rebuild.addAll(indexes.keySet());
                rebuild.addAll(pendingWarmupSet);
                if (activeWarmup != null) rebuild.add(activeWarmup.advancements());
                indexes.clear();
                pendingWarmups.clear();
                pendingWarmupSet.clear();
                activeWarmup = null;
                planCompiler.clear();
                for (PlayerAdvancements advancements : rebuild) queueWarmup(advancements);
            }
        } else {
            suspendIndexMaintenance();
        }
        reloadInProgress = false;
    }

    public void requestVerification() {
        synchronized (indexes) {
            for (PlayerIndex index : indexes.values()) index.requestVerification();
        }
    }

    public void resetPlayerCircuitBreakers() {
        synchronized (indexes) {
            indexes.clear();
            pendingWarmups.clear();
            pendingWarmupSet.clear();
            activeWarmup = null;
            indexMaintenanceSuspended = true;
        }
        consecutiveMismatches = 0;
    }

    public void clear() {
        synchronized (indexes) {
            indexes.clear();
            pendingWarmups.clear();
            pendingWarmupSet.clear();
            activeWarmup = null;
            planCompiler.clear();
        }
        reloadInProgress = false;
        activePredicateScopes.set(0);
        processing.remove();
    }

    public void setMode(OptimizationMode mode) {
        commandMode = mode;
        if (mode == OptimizationMode.VANILLA) suspendIndexMaintenance();
        if (mode != OptimizationMode.VANILLA && replacementHealthy) disabledReason = "none";
    }

    public OptimizationMode mode() {
        OptimizationMode override = commandMode;
        return override != null ? override : OptimizationMode.parse(InvAdvOptConfig.MODE.get());
    }

    public String status() {
        PlanCompiler.CacheStats planCache = planCompiler.cacheStats();
        return "mode=" + mode() + ", enabled=" + InvAdvOptConfig.ENABLED.get() + ", replacementHealthy=" + replacementHealthy
                + ", reloadInProgress=" + reloadInProgress + ", registryGeneration=" + registryGeneration.get()
                + ", indexedPlayers=" + indexedPlayers() + ", indexMaintenanceSuspended=" + indexMaintenanceSuspended
                + ", pendingWarmups=" + pendingWarmupCount() + ", planCache=" + planCache.size()
                + "/" + planCache.hits() + "/" + planCache.misses() + ", disabledReason=" + disabledReason;
    }

    public int indexedPlayers() {
        synchronized (indexes) { return indexes.size(); }
    }

    private int pendingWarmupCount() {
        synchronized (indexes) {
            return pendingWarmupSet.size() + (activeWarmup == null ? 0 : 1);
        }
    }

    public StatsCollector stats() {
        return stats;
    }

    private int listenerCount(ServerPlayer player) {
        synchronized (indexes) {
            PlayerIndex index = indexes.get(player.getAdvancements());
            return index == null ? 0 : index.listenerCount();
        }
    }

    private static String safeItemName(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static final class ProcessingState {
        private boolean optimizing;
        private int predicateScopeDepth;
        private long predicateTests;
        private final LazyMatchContext matchContext = new LazyMatchContext();
        private final Deque<VanillaFrame> vanillaFrames = new ArrayDeque<>();
    }

    private static final class LazyMatchContext {
        private ServerPlayer player;
        private LootContext context;

        private void reset(ServerPlayer player) {
            this.player = player;
            context = null;
        }

        private LootContext get() {
            if (context == null) context = EntityPredicate.createContext(player, player);
            return context;
        }

        private void clear() {
            player = null;
            context = null;
        }
    }

    private record VanillaFrame(ServerPlayer player, ItemStack stack, int rawListeners, String reason, long started,
            boolean trackPredicates) {}

    private record Verification(boolean matches,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> fullMatches,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> missing,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> extra) {}

    private record WarmupTask(
            PlayerAdvancements advancements,
            PlayerIndex.Builder builder,
            long registryGeneration) {
    }
}
