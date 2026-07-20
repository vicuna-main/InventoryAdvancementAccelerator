package dev.invadvopt;

import com.mojang.logging.LogUtils;
import dev.invadvopt.config.InvAdvOptConfig;
import dev.invadvopt.index.IdentitySet;
import dev.invadvopt.index.PlayerIndex;
import dev.invadvopt.metrics.StatsCollector;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.advancements.CriterionTrigger;
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

    private final Map<PlayerAdvancements, PlayerIndex> indexes = new IdentityHashMap<>();
    private final AtomicLong registryGeneration = new AtomicLong();
    private final StatsCollector stats = new StatsCollector();
    private final ThreadLocal<ProcessingState> processing = ThreadLocal.withInitial(ProcessingState::new);

    private volatile OptimizationMode commandMode;
    private volatile boolean reloadInProgress;
    private volatile boolean replacementHealthy;
    private volatile String disabledReason = "server_not_started";
    private int consecutiveMismatches;

    public void startupSelfCheck() {
        stats.setEnabled(InvAdvOptConfig.METRICS_ENABLED.get());
        List<String> conflicts = CONFLICTING_MODS.stream().filter(id -> ModList.get().isLoaded(id)).toList();
        if (!conflicts.isEmpty() || Boolean.getBoolean("invadvopt.mixin.conflict")) {
            replacementHealthy = false;
            disabledReason = "conflicting_mod:" + String.join(",", conflicts);
            LOGGER.error("[invadvopt] Trigger replacement refused because a conflicting advancement optimizer is loaded: {}. Vanilla behavior is retained.", conflicts);
            return;
        }

        List<String> missingHooks = new ArrayList<>();
        for (String hook : List.of("listener", "trigger", "predicate", "reload")) {
            if (!Boolean.getBoolean("invadvopt.mixin." + hook)) missingHooks.add(hook);
        }
        if (!verifyVanillaDescriptors()) missingHooks.add("vanilla_descriptors");
        if (!missingHooks.isEmpty()) {
            replacementHealthy = false;
            disabledReason = "mixin_self_check:" + String.join(",", missingHooks);
            LOGGER.error("[invadvopt] Mixin self-check failed ({}). Optimization is disabled; the original trigger remains active.", missingHooks);
            return;
        }
        replacementHealthy = true;
        disabledReason = "none";
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
    public void addListener(PlayerAdvancements advancements, CriterionTrigger.Listener<?> listener) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> typed =
                (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>) listener;
        synchronized (indexes) {
            indexes.computeIfAbsent(advancements, ignored -> new PlayerIndex()).add(typed, registryGeneration.get());
        }
    }

    @SuppressWarnings("unchecked")
    public void removeListener(PlayerAdvancements advancements, CriterionTrigger.Listener<?> listener) {
        synchronized (indexes) {
            PlayerIndex index = indexes.get(advancements);
            if (index != null) index.remove((CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>) listener);
        }
    }

    public void removeListeners(PlayerAdvancements advancements) {
        synchronized (indexes) {
            indexes.remove(advancements);
        }
    }

    public boolean handleTrigger(ServerPlayer player, Inventory inventory, ItemStack changedStack) {
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
            index = indexes.get(player.getAdvancements());
        }
        if (index == null) {
            beginVanilla(state, player, changedStack, 0, "index_missing");
            return false;
        }

        state.optimizing = true;
        long started = System.nanoTime();
        stats.startPredicateScope();
        int rawCount = index.listenerCount();
        int candidateCount = rawCount;
        try {
            int full = 0;
            int empty = 0;
            int occupied = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    empty++;
                } else {
                    occupied++;
                    if (stack.getCount() >= stack.getMaxStackSize()) full++;
                }
            }

            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> awards;
            long tick = player.getServer().getTickCount();
            synchronized (index) {
                PlayerIndex.Selection selection = index.select(inventory, changedStack, registryGeneration.get(), tick,
                        InvAdvOptConfig.PERIODIC_FULL_SCAN_TICKS.get());
                rawCount = selection.allListeners().size();
                candidateCount = selection.candidates().size();
                if (selection.disabled()) {
                    return fallThroughFromOptimized(state, player, changedStack, rawCount, "player_index_disabled", started);
                }
                if (selection.unsafe() && (mode() == OptimizationMode.EXACT || InvAdvOptConfig.FALLBACK_ON_UNKNOWN_PREDICATE.get())) {
                    return fallThroughFromOptimized(state, player, changedStack, rawCount, "unknown_or_incomplete_index", started);
                }

                boolean sampleVerify = selection.verify()
                        || ThreadLocalRandom.current().nextDouble() < InvAdvOptConfig.SHADOW_VERIFY_RATE.get();
                if (selection.mandatoryFull()) stats.recordFullScan();
                LootContext context = EntityPredicate.createContext(player, player);
                List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> optimizedMatches =
                        evaluate(selection.candidates(), inventory, changedStack, full, empty, occupied, context);
                awards = optimizedMatches;

                if (sampleVerify && !selection.mandatoryFull()) {
                    stats.recordFullScan();
                    Verification verification = verifyFull(selection.allListeners(), selection.candidates(), optimizedMatches,
                            inventory, changedStack, full, empty, occupied, context);
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
            stats.recordTrigger(player, changedStack, rawCount, candidateCount, System.nanoTime() - started);
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("[invadvopt] Optimized trigger failed safely for player {} and item {}; retrying through vanilla.",
                    player.getUUID(), safeItemName(changedStack), throwable);
            return fallThroughFromOptimized(state, player, changedStack, rawCount, "optimization_exception", started);
        } finally {
            if (state.optimizing) {
                stats.endPredicateScope();
                state.optimizing = false;
            }
        }
    }

    private List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> evaluate(
            Collection<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source,
            Inventory inventory, ItemStack changedStack, int full, int empty, int occupied, LootContext context) {
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> result = null;
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : source) {
            InventoryChangeTrigger.TriggerInstance trigger = listener.trigger();
            if (trigger.matches(inventory, changedStack, full, empty, occupied)) {
                Optional<ContextAwarePredicate> playerPredicate = trigger.player();
                if (playerPredicate.isEmpty() || playerPredicate.get().matches(context)) {
                    if (result == null) result = new ArrayList<>();
                    result.add(listener);
                }
            }
        }
        return result;
    }

    private Verification verifyFull(
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> all,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidates,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> optimizedMatches,
            Inventory inventory, ItemStack changedStack, int full, int empty, int occupied, LootContext context) {
        Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> candidateSet = IdentitySet.create();
        candidateSet.addAll(candidates);
        Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> optimizedSet = IdentitySet.create();
        if (optimizedMatches != null) optimizedSet.addAll(optimizedMatches);
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> fullMatches =
                optimizedMatches == null ? null : new ArrayList<>(optimizedMatches);
        Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> fullSet = IdentitySet.create();
        fullSet.addAll(optimizedSet);

        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : all) {
            if (candidateSet.contains(listener)) continue;
            InventoryChangeTrigger.TriggerInstance trigger = listener.trigger();
            if (trigger.matches(inventory, changedStack, full, empty, occupied)) {
                Optional<ContextAwarePredicate> playerPredicate = trigger.player();
                if (playerPredicate.isEmpty() || playerPredicate.get().matches(context)) {
                    if (fullMatches == null) fullMatches = new ArrayList<>();
                    fullMatches.add(listener);
                    fullSet.add(listener);
                }
            }
        }
        Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> missing = IdentitySet.create();
        missing.addAll(fullSet);
        missing.removeAll(optimizedSet);
        Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> extra = IdentitySet.create();
        extra.addAll(optimizedSet);
        extra.removeAll(fullSet);
        return new Verification(missing.isEmpty() && extra.isEmpty(), fullMatches, missing, extra);
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

    private void logMismatch(String type, Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> listeners,
            ServerPlayer player, ItemStack stack, PlayerIndex.Selection selection) {
        for (CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener : listeners) {
            LOGGER.error("[invadvopt] Shadow mismatch type={} advancement={} criterion={} player={} item={} listenerGeneration={} registryGeneration={}",
                    type, listener.advancement().id(), listener.criterion(), player.getUUID(), safeItemName(stack),
                    selection.listenerGeneration(), selection.registryGeneration());
        }
    }

    private String unavailableReason(ServerPlayer player) {
        if (!InvAdvOptConfig.ENABLED.get()) return "disabled_by_config";
        if (mode() == OptimizationMode.VANILLA) return "vanilla_mode";
        if (!replacementHealthy) return disabledReason;
        if (reloadInProgress) return "datapack_reload_in_progress";
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) return "off_thread_call";
        return null;
    }

    private boolean fallThroughFromOptimized(ProcessingState state, ServerPlayer player, ItemStack stack,
            int rawCount, String reason, long started) {
        stats.endPredicateScope();
        state.optimizing = false;
        beginVanilla(state, player, stack, rawCount, reason, started);
        return false;
    }

    private void beginVanilla(ProcessingState state, ServerPlayer player, ItemStack stack, int rawCount, String reason) {
        beginVanilla(state, player, stack, rawCount, reason, System.nanoTime());
    }

    private void beginVanilla(ProcessingState state, ServerPlayer player, ItemStack stack, int rawCount, String reason, long started) {
        stats.recordFallback(reason);
        stats.startPredicateScope();
        state.vanillaFrames.push(new VanillaFrame(player, stack, rawCount, reason, started));
    }

    public void onVanillaTriggerReturn() {
        ProcessingState state = processing.get();
        VanillaFrame frame = state.vanillaFrames.poll();
        if (frame == null) return;
        stats.endPredicateScope();
        stats.recordTrigger(frame.player(), frame.stack(), frame.rawListeners(), frame.rawListeners(), System.nanoTime() - frame.started());
        if (state.vanillaFrames.isEmpty() && !state.optimizing) processing.remove();
    }

    public void onItemPredicateTest() {
        stats.onItemPredicateTest();
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
        long generation = registryGeneration.incrementAndGet();
        synchronized (indexes) {
            for (PlayerIndex index : indexes.values()) index.markReload(generation);
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
            for (PlayerIndex index : indexes.values()) index.enable();
        }
        consecutiveMismatches = 0;
    }

    public void clear() {
        synchronized (indexes) { indexes.clear(); }
        reloadInProgress = false;
        processing.remove();
    }

    public void setMode(OptimizationMode mode) {
        commandMode = mode;
        if (mode != OptimizationMode.VANILLA && replacementHealthy) disabledReason = "none";
    }

    public OptimizationMode mode() {
        OptimizationMode override = commandMode;
        return override != null ? override : OptimizationMode.parse(InvAdvOptConfig.MODE.get());
    }

    public String status() {
        return "mode=" + mode() + ", enabled=" + InvAdvOptConfig.ENABLED.get() + ", replacementHealthy=" + replacementHealthy
                + ", reloadInProgress=" + reloadInProgress + ", registryGeneration=" + registryGeneration.get()
                + ", indexedPlayers=" + indexedPlayers() + ", disabledReason=" + disabledReason;
    }

    public int indexedPlayers() {
        synchronized (indexes) { return indexes.size(); }
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
        private final Deque<VanillaFrame> vanillaFrames = new ArrayDeque<>();
    }

    private record VanillaFrame(ServerPlayer player, ItemStack stack, int rawListeners, String reason, long started) {}

    private record Verification(boolean matches,
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> fullMatches,
            Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> missing,
            Set<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> extra) {}
}
