package dev.invadvopt.index;

import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;

public record CompiledPlan(
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener,
        Set<Integer> rawItemIds,
        boolean alwaysCheck,
        boolean wildcard,
        boolean slotSensitive,
        boolean indexSafe) {
}
