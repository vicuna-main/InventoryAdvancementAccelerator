package dev.invadvopt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class CandidateSelectionBenchmarkTest {
    private static volatile long blackhole;

    @Test
    void fiveThousandListenerFiftyPlayerSyntheticLoad() {
        int listeners = 5_000;
        int itemKinds = 1_000;
        int players = 50;
        int measuredTicks = 200;
        int[] listenerItems = new int[listeners];
        List<List<Integer>> byItem = new ArrayList<>(itemKinds);
        for (int i = 0; i < itemKinds; i++) byItem.add(new ArrayList<>());
        Random random = new Random(20260719L);
        for (int listener = 0; listener < listeners; listener++) {
            int item = random.nextInt(itemKinds);
            listenerItems[listener] = item;
            byItem.get(item).add(listener);
        }

        for (int warmup = 0; warmup < 20; warmup++) {
            runVanilla(listenerItems, players, warmup);
            runIndexed(byItem, players, warmup);
        }
        long vanillaStarted = System.nanoTime();
        for (int tick = 0; tick < measuredTicks; tick++) runVanilla(listenerItems, players, tick);
        long vanillaNanos = System.nanoTime() - vanillaStarted;
        long indexedStarted = System.nanoTime();
        long candidateCount = 0;
        for (int tick = 0; tick < measuredTicks; tick++) candidateCount += runIndexed(byItem, players, tick);
        long indexedNanos = System.nanoTime() - indexedStarted;
        double averageCandidates = (double)candidateCount / (players * measuredTicks);
        double reduction = 1.0D - averageCandidates / listeners;
        double speedup = (double)vanillaNanos / Math.max(1L, indexedNanos);
        System.out.printf(java.util.Locale.ROOT,
                "INVADVOPT_BENCHMARK listeners=%d players=%d ticks=%d avgCandidates=%.3f reduction=%.3f%% vanillaMs=%.3f indexedMs=%.3f speedup=%.2fx%n",
                listeners, players, measuredTicks, averageCandidates, reduction * 100.0D,
                vanillaNanos / 1_000_000.0D, indexedNanos / 1_000_000.0D, speedup);
        assertTrue(reduction >= 0.80D);
        assertTrue(blackhole != Long.MIN_VALUE);
    }

    private static void runVanilla(int[] listenerItems, int players, int tick) {
        long value = blackhole;
        for (int player = 0; player < players; player++) {
            int changedItem = (tick * 31 + player) % 1_000;
            for (int listenerItem : listenerItems) {
                if (listenerItem == changedItem) value++;
            }
        }
        blackhole = value;
    }

    private static long runIndexed(List<List<Integer>> byItem, int players, int tick) {
        long value = blackhole;
        long candidates = 0;
        for (int player = 0; player < players; player++) {
            int changedItem = (tick * 31 + player) % 1_000;
            List<Integer> indexed = byItem.get(changedItem);
            candidates += indexed.size();
            for (int listener : indexed) value += listener & 1;
        }
        blackhole = value;
        return candidates;
    }
}
