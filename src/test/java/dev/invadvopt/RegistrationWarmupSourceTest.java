package dev.invadvopt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RegistrationWarmupSourceTest {
    @Test
    void absentIndexesAreNeverMaterializedByIndividualListenerCallbacks() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/invadvopt/InventoryAdvancementRuntime.java"));
        String addListener = between(source, "public void addListener(", "public void removeListener(");
        String removeListener = between(source, "public void removeListener(", "public void removeListeners(");

        assertTrue(addListener.contains("if (index == null) return;"));
        assertFalse(addListener.contains("computeIfAbsent"));
        assertFalse(addListener.contains("rebuildIndex"));
        assertTrue(removeListener.contains("if (index == null) return;"));
        assertFalse(removeListener.contains("rebuildIndex"));
    }

    @Test
    void triggerFallsThroughWhileThePrivateIndexIsWarming() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/invadvopt/InventoryAdvancementRuntime.java"));
        String trigger = between(source, "public boolean handleTrigger(", "private List<CriterionTrigger.Listener");

        assertTrue(trigger.contains("queueWarmup(player.getAdvancements())"));
        assertTrue(trigger.contains("\"index_warming\""));
        assertFalse(trigger.contains("rebuildIndex"));
    }

    @Test
    void fullRegistrationHookRunsOnlyAfterVanillaReturns() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/invadvopt/mixin/PlayerAdvancementsMixin.java"));

        assertTrue(source.contains("registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V"));
        assertTrue(source.contains("at = @At(\"RETURN\")"));
        assertTrue(source.contains("RUNTIME.listenersRegistered"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "source boundaries not found");
        return source.substring(from, to);
    }
}
