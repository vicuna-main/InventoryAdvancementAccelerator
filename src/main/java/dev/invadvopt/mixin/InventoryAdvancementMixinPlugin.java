package dev.invadvopt.mixin;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class InventoryAdvancementMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> CONFLICTS = Set.of("achiopt", "cerulean", "icterine");
    private static final String LISTENER_DESCRIPTOR = "(Lnet/minecraft/server/PlayerAdvancements;Lnet/minecraft/advancements/CriterionTrigger$Listener;)V";
    private static final String TRIGGER_DESCRIPTOR = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/ItemStack;)V";
    private static final String RELOAD_DESCRIPTOR = "(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            LoadingModList loading = LoadingModList.get();
            if (loading != null) {
                for (String id : CONFLICTS) {
                    if (loading.getModFileById(id) != null) {
                        System.setProperty("invadvopt.mixin.conflict", "true");
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
            // The mod entry point repeats this check after loading is complete.
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String hook = switch (targetClassName) {
            case "net.minecraft.advancements.critereon.SimpleCriterionTrigger" -> "listener";
            case "net.minecraft.advancements.critereon.InventoryChangeTrigger" -> "trigger";
            case "net.minecraft.advancements.critereon.ItemPredicate" -> "predicate";
            case "net.minecraft.server.MinecraftServer" -> "reload";
            case "net.minecraft.server.PlayerAdvancements" -> "bulk";
            default -> null;
        };
        if (hook == null) return;
        boolean complete = switch (hook) {
            case "listener" -> containsInjectedCall(targetClass, "addPlayerListener", LISTENER_DESCRIPTOR, "invadvopt$onListenerAdded")
                    && containsInjectedCall(targetClass, "removePlayerListener", LISTENER_DESCRIPTOR, "invadvopt$onListenerRemoved")
                    && containsInjectedCall(targetClass, "removePlayerListeners", "(Lnet/minecraft/server/PlayerAdvancements;)V", "invadvopt$onAllListenersRemoved");
            case "trigger" -> containsInjectedCall(targetClass, "trigger", TRIGGER_DESCRIPTOR, "invadvopt$replaceTrigger")
                    && containsInjectedCall(targetClass, "trigger", TRIGGER_DESCRIPTOR, "invadvopt$finishVanillaTrigger");
            case "predicate" -> containsInjectedCall(targetClass, "test", "(Lnet/minecraft/world/item/ItemStack;)Z", "invadvopt$countPredicateTest");
            case "reload" -> containsInjectedCall(targetClass, "reloadResources", RELOAD_DESCRIPTOR, "invadvopt$reloadStarted")
                    && containsInjectedCall(targetClass, "reloadResources", RELOAD_DESCRIPTOR, "invadvopt$reloadFuture");
            case "bulk" -> containsInjectedCall(targetClass, "registerListeners", "(Lnet/minecraft/server/ServerAdvancementManager;)V", "invadvopt$listenersRegistered");
            default -> false;
        };
        // A partial optional injection must not leave cancellation enabled. Inspect each
        // exact target, not just the presence of any invadvopt call anywhere in the class.
        System.setProperty("invadvopt.mixin." + hook, Boolean.toString(complete));
    }

    private static boolean containsInjectedCall(ClassNode targetClass, String targetMethod, String descriptor, String marker) {
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethod) || !method.desc.equals(descriptor)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode invocation && invocation.owner.equals(targetClass.name)
                        && invocation.name.endsWith(marker)) {
                    return true;
                }
            }
        }
        return false;
    }
}
