package dev.invadvopt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DifferentialSelectorTest {
    @Test
    void randomizedExactCandidatesNeverMissVanillaMatch() {
        Random random = new Random(0x1A21_1A11L);
        List<Listener> listeners = randomListeners(random, 5_000, 512);
        List<Stack> previous = randomInventory(random, 36, 512);

        for (int event = 0; event < 10_000; event++) {
            List<Stack> current = new ArrayList<>(previous);
            int changes = 1 + random.nextInt(4);
            int changedSlot = 0;
            for (int i = 0; i < changes; i++) {
                changedSlot = random.nextInt(current.size());
                Stack old = current.get(changedSlot);
                int operation = random.nextInt(5);
                Stack replacement = switch (operation) {
                    case 0 -> Stack.EMPTY;
                    case 1 -> new Stack(old.item(), Math.max(0, old.count() - 1), old.componentVersion());
                    case 2 -> new Stack(old.item(), old.count(), old.componentVersion() + 1);
                    case 3 -> new Stack(random.nextInt(512), 1 + random.nextInt(64), random.nextInt(32));
                    default -> new Stack(old.item(), old.count() + 1, old.componentVersion());
                };
                current.set(changedSlot, replacement.count() == 0 ? Stack.EMPTY : replacement);
            }
            Stack changedStack = current.get(changedSlot);
            Set<Integer> changedIds = changedIds(previous, current);
            if (!changedStack.empty()) changedIds.add(changedStack.item());

            // Listener registration/login performs a mandatory full event. Thereafter a
            // matched criterion is removed exactly once by PlayerAdvancements.award.
            Set<Integer> optimized = matches(listeners, current, changedStack, changedIds, event == 0);
            Set<Integer> vanilla = matches(listeners, current, changedStack, changedIds, true);
            assertEquals(vanilla, optimized, "candidate mismatch at event " + event);
            if (!vanilla.isEmpty()) listeners.removeIf(listener -> vanilla.contains(listener.id()));
            previous = current;
        }
    }

    @Test
    void explicitItemIndexReducesFiveThousandListenersByAtLeastEightyPercent() {
        Random random = new Random(42L);
        List<Listener> listeners = randomListeners(random, 5_000, 1_000);
        Set<Integer> changed = Set.of(17);
        long candidates = listeners.stream().filter(listener -> listener.always() || listener.wildcard()
                || listener.slotSensitive() || intersects(listener.indexedItems(), changed)).count();
        double reduction = 1.0D - (double)candidates / listeners.size();
        assertTrue(reduction >= 0.80D, "reduction was " + reduction);
    }

    @Test
    void matchingIsTwoPhaseUnderReentrantListenerMutation() {
        List<Integer> listeners = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> matched = new ArrayList<>();
        for (int listener : listeners) {
            if (listener > 0) matched.add(listener);
        }
        List<Integer> ran = new ArrayList<>();
        for (int listener : matched) {
            ran.add(listener);
            if (listener == 1) listeners.clear();
        }
        assertEquals(List.of(1, 2, 3), ran);
    }

    private static List<Listener> randomListeners(Random random, int count, int itemKinds) {
        List<Listener> result = new ArrayList<>(count);
        for (int id = 0; id < count; id++) {
            boolean wildcard = random.nextInt(100) == 0;
            boolean player = random.nextInt(100) == 0;
            boolean slots = random.nextInt(100) == 0;
            int predicateCount = 1 + random.nextInt(3);
            Set<Integer> items = new HashSet<>();
            for (int i = 0; i < predicateCount; i++) items.add(random.nextInt(itemKinds));
            result.add(new Listener(id, Set.copyOf(items), wildcard, player, slots, predicateCount));
        }
        return result;
    }

    private static List<Stack> randomInventory(Random random, int slots, int itemKinds) {
        List<Stack> result = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            if (random.nextInt(5) == 0) result.add(Stack.EMPTY);
            else result.add(new Stack(random.nextInt(itemKinds), 1 + random.nextInt(64), random.nextInt(32)));
        }
        return result;
    }

    private static Set<Integer> matches(List<Listener> listeners, List<Stack> inventory, Stack changedStack,
            Set<Integer> changedIds, boolean fullScan) {
        Set<Integer> result = new HashSet<>();
        for (Listener listener : listeners) {
            boolean candidate = fullScan || listener.always() || listener.wildcard() || listener.slotSensitive()
                    || intersects(listener.indexedItems(), changedIds);
            if (candidate && originalMatches(listener, inventory, changedStack)) result.add(listener.id());
        }
        return result;
    }

    private static boolean originalMatches(Listener listener, List<Stack> inventory, Stack changedStack) {
        if (listener.wildcard()) return inventory.stream().anyMatch(stack -> !stack.empty());
        if (listener.predicateCount() == 1) return !changedStack.empty() && listener.indexedItems().contains(changedStack.item());
        Map<Integer, Boolean> needed = new HashMap<>();
        listener.indexedItems().forEach(item -> needed.put(item, false));
        for (Stack stack : inventory) if (!stack.empty() && needed.containsKey(stack.item())) needed.put(stack.item(), true);
        return needed.values().stream().allMatch(Boolean::booleanValue);
    }

    private static Set<Integer> changedIds(List<Stack> previous, List<Stack> current) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < previous.size(); i++) {
            Stack old = previous.get(i);
            Stack now = current.get(i);
            if (!old.equals(now)) {
                if (!old.empty()) result.add(old.item());
                if (!now.empty()) result.add(now.item());
            }
        }
        return result;
    }

    private static boolean intersects(Set<Integer> left, Set<Integer> right) {
        for (int value : left) if (right.contains(value)) return true;
        return false;
    }

    private record Listener(int id, Set<Integer> indexedItems, boolean wildcard, boolean playerSensitive,
                            boolean slotSensitive, int predicateCount) {
        boolean always() { return playerSensitive; }
    }

    private record Stack(int item, int count, int componentVersion) {
        private static final Stack EMPTY = new Stack(-1, 0, 0);
        boolean empty() { return count <= 0; }
    }
}
