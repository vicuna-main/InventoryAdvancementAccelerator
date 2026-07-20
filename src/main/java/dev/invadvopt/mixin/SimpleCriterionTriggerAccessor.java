package dev.invadvopt.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleCriterionTrigger.class)
public interface SimpleCriterionTriggerAccessor {
    @Accessor("players")
    Map<PlayerAdvancements, Set<CriterionTrigger.Listener<?>>> invadvopt$getPlayers();
}
