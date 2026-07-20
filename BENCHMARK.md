# Benchmark report

## Synthetic candidate-layer run

Environment: Windows 10, Java 21.0.10, Gradle 8.12.1, 19 July 2026.

Command:

```powershell
.\gradlew.bat test --tests dev.invadvopt.CandidateSelectionBenchmarkTest --info --offline
```

Workload: 5,000 explicit-item listeners, 1,000 item IDs, 50 players, 200 measured ticks, and one changed item per player/tick after warm-up.

| Metric | Result |
|---|---:|
| Average candidates/event | 5.014 |
| Candidate reduction | 99.900% |
| Full model loop | 5.609 ms |
| Indexed model loop | 1.389 ms |
| Candidate-layer speedup | 4.04x |

This is a deterministic microbenchmark of the coarse selection layer, not a claim about whole-server MSPT or allocation rate. It intentionally does not model the much more expensive real `ItemPredicate.test`, so the ratio is not substituted for an on-server profile.

## Production measurement procedure

Use identical worlds/data packs and the same 9-50 scripted players in `VANILLA` and `EXACT`. Warm up for five minutes, then record at least ten minutes with Java Flight Recorder or async-profiler. Each player should have at least one Data Component mutation per tick. Compare `InventoryChangeTrigger` wall time, server-thread allocation rate, GC pauses, candidate reduction, and shadow mismatches. Do not accept a run with any mismatch.

The repository test environment has no 9-50 player load generator, so the requested >=60% real trigger-time and non-increased-GC acceptance thresholds must be confirmed on the target modpack. Runtime `/invadvopt stats` supplies the trigger-side counters needed for that comparison.
