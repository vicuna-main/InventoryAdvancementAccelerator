package dev.invadvopt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.invadvopt.mixin.InventoryAdvancementMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class MixinHookValidationTest {
    private static final String LISTENER_OWNER = "net.minecraft.advancements.critereon.SimpleCriterionTrigger";
    private static final String LISTENER_ARGS = "(Lnet/minecraft/server/PlayerAdvancements;Lnet/minecraft/advancements/CriterionTrigger$Listener;)V";
    private static final String REMOVE_ALL_ARGS = "(Lnet/minecraft/server/PlayerAdvancements;)V";
    private static final String TRIGGER_OWNER = "net.minecraft.advancements.critereon.InventoryChangeTrigger";
    private static final String TRIGGER_ARGS = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/ItemStack;)V";

    @Test
    void aSingleListenerHookMustNotEnableReplacement() {
        ClassNode node = node(LISTENER_OWNER);
        node.methods.add(method(node, "addPlayerListener", LISTENER_ARGS, "invadvopt$onListenerAdded"));
        assertFalse(check("listener", LISTENER_OWNER, node));
    }

    @Test
    void allThreeLifecycleHooksAreRequiredIndependently() {
        for (int missing = 0; missing < 3; missing++) {
            ClassNode node = completeListenerNode();
            node.methods.get(missing).instructions.clear();
            assertFalse(check("listener", LISTENER_OWNER, node), "missing hook " + missing + " was accepted");
        }
        assertTrue(check("listener", LISTENER_OWNER, completeListenerNode()));
    }

    @Test
    void hooksInWrongMethodsOrDescriptorsDoNotCount() {
        ClassNode wrongDescriptor = completeListenerNode();
        wrongDescriptor.methods.getFirst().desc = "()V";
        assertFalse(check("listener", LISTENER_OWNER, wrongDescriptor));
        ClassNode wrongMethod = completeListenerNode();
        wrongMethod.methods.get(1).name = "unrelatedMethod";
        assertFalse(check("listener", LISTENER_OWNER, wrongMethod));
    }

    @Test
    void unrelatedOwnersCannotImpersonateAnInjectedHook() {
        ClassNode node = completeListenerNode();
        ((MethodInsnNode) node.methods.getFirst().instructions.getFirst()).owner = "unrelated/Helper";
        assertFalse(check("listener", LISTENER_OWNER, node));
    }

    @Test
    void triggerRequiresReplacementAndReturnBookkeeping() {
        ClassNode node = node(TRIGGER_OWNER);
        node.methods.add(method(node, "trigger", TRIGGER_ARGS, "invadvopt$replaceTrigger"));
        assertFalse(check("trigger", TRIGGER_OWNER, node));
        node.methods.getFirst().instructions.add(call(node, "invadvopt$finishVanillaTrigger"));
        assertTrue(check("trigger", TRIGGER_OWNER, node));
        node.methods.getFirst().instructions.remove(node.methods.getFirst().instructions.getFirst());
        assertFalse(check("trigger", TRIGGER_OWNER, node));
    }

    @Test
    void reloadRequiresBothStartAndCompletionHooks() {
        String owner = "net.minecraft.server.MinecraftServer";
        ClassNode node = node(owner);
        node.methods.add(method(node, "reloadResources", "(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;", "invadvopt$reloadStarted"));
        assertFalse(check("reload", owner, node));
        node.methods.getFirst().instructions.add(call(node, "invadvopt$reloadFuture"));
        assertTrue(check("reload", owner, node));
    }

    @Test
    void predicateAndOptionalBulkHooksUseExactTargets() {
        String predicateOwner = "net.minecraft.advancements.critereon.ItemPredicate";
        ClassNode predicate = node(predicateOwner);
        predicate.methods.add(method(predicate, "test", "(Lnet/minecraft/world/item/ItemStack;)Z", "invadvopt$countPredicateTest"));
        assertTrue(check("predicate", predicateOwner, predicate));
        predicate.methods.getFirst().desc = "(Ljava/lang/Object;)Z";
        assertFalse(check("predicate", predicateOwner, predicate));

        String bulkOwner = "net.minecraft.server.PlayerAdvancements";
        ClassNode bulk = node(bulkOwner);
        bulk.methods.add(method(bulk, "registerListeners", "(Lnet/minecraft/server/ServerAdvancementManager;)V", "invadvopt$listenersRegistered"));
        assertTrue(check("bulk", bulkOwner, bulk));
        bulk.methods.getFirst().name = "anotherRegistration";
        assertFalse(check("bulk", bulkOwner, bulk));
    }

    @Test
    void subsequentIncompleteInspectionClearsAStaleSuccess() {
        String key = "invadvopt.mixin.listener";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            new InventoryAdvancementMixinPlugin().postApply(LISTENER_OWNER, node(LISTENER_OWNER), "test", null);
            assertEquals("false", System.getProperty(key));
        } finally {
            restore(key, previous);
        }
    }

    private static ClassNode completeListenerNode() {
        ClassNode node = node(LISTENER_OWNER);
        node.methods.add(method(node, "addPlayerListener", LISTENER_ARGS, "invadvopt$onListenerAdded"));
        node.methods.add(method(node, "removePlayerListener", LISTENER_ARGS, "invadvopt$onListenerRemoved"));
        node.methods.add(method(node, "removePlayerListeners", REMOVE_ALL_ARGS, "invadvopt$onAllListenersRemoved"));
        return node;
    }

    private static ClassNode node(String owner) {
        ClassNode node = new ClassNode();
        node.name = owner.replace('.', '/');
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor, String marker) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(call(owner, marker));
        return method;
    }

    private static MethodInsnNode call(ClassNode owner, String marker) {
        return new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, "handler$synthetic$" + marker, "()V", false);
    }

    private static boolean check(String category, String owner, ClassNode node) {
        String key = "invadvopt.mixin." + category;
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            new InventoryAdvancementMixinPlugin().postApply(owner, node, "test", null);
            return Boolean.getBoolean(key);
        } finally {
            restore(key, previous);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
