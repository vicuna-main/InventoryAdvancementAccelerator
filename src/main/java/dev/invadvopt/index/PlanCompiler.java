package dev.invadvopt.index;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

public final class PlanCompiler {
    private final Map<InventoryChangeTrigger.TriggerInstance, CacheEntry> templates = new IdentityHashMap<>();
    private long cacheHits;
    private long cacheMisses;

    public synchronized CompiledPlan compile(
            CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener,
            long registryGeneration) {
        InventoryChangeTrigger.TriggerInstance trigger = listener.trigger();
        CacheEntry cached = templates.get(trigger);
        if (cached != null && cached.registryGeneration() == registryGeneration) {
            cacheHits++;
            return CompiledPlan.bind(listener, cached.template());
        }

        CompiledPlanTemplate template = compileTemplate(trigger);
        templates.put(trigger, new CacheEntry(registryGeneration, template));
        cacheMisses++;
        return CompiledPlan.bind(listener, template);
    }

    public synchronized void clear() {
        templates.clear();
    }

    public synchronized CacheStats cacheStats() {
        return new CacheStats(cacheHits, cacheMisses, templates.size());
    }

    private static CompiledPlanTemplate compileTemplate(InventoryChangeTrigger.TriggerInstance trigger) {
        Set<Integer> rawIds = new HashSet<>();
        boolean always = trigger.items().isEmpty() || trigger.player().isPresent();
        boolean wildcard = false;
        boolean safe = true;

        try {
            for (ItemPredicate predicate : trigger.items()) {
                if (predicate.items().isEmpty()) {
                    wildcard = true;
                    continue;
                }
                HolderSet<Item> holders = predicate.items().orElseThrow();
                for (Holder<Item> holder : holders) {
                    int rawId = BuiltInRegistries.ITEM.getId(holder.value());
                    if (rawId < 0) {
                        safe = false;
                    } else {
                        rawIds.add(rawId);
                    }
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            safe = false;
        }

        InventoryChangeTrigger.TriggerInstance.Slots slots = trigger.slots();
        boolean slotSensitive = !slots.full().isAny() || !slots.empty().isAny() || !slots.occupied().isAny();
        if (!safe) {
            always = true;
        }
        return new CompiledPlanTemplate(Set.copyOf(rawIds), always, wildcard, slotSensitive, safe);
    }

    public record CacheStats(long hits, long misses, int size) {
    }

    private record CacheEntry(long registryGeneration, CompiledPlanTemplate template) {
    }
}
