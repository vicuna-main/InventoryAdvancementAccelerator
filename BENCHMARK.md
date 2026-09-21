# Benchmark report

## 1.0.3：预热移除场景的确定性工作量比较

`ProfileRegressionGameTests.removalChurnCompilesFewerEntriesThanRestartProtocol` 使用实际的 `PlayerIndex.Builder` 和 `PlanCompiler`，对比旧版“每个回调都丢弃预热”的协议与候选协议。

固定输入为 512 个监听器，每片编译 16 项，在前 31 片之后各移除一个监听器；最终两边都必须发布同一组 481 个有效监听器。旧协议需要 977 次计划绑定，候选需要 512 次，减少约 47.6% 的绑定工作。这里比较的是确定性工作量，未测量整个服务器的 MSPT、GC 或真实玩家吞吐，不作整服提速承诺。

新增快照检查覆盖装备、副手和扩展槽位，以完整性为目标；相对旧版只比较前 36 格，会增加这些槽位的必要比较。预热时间预算约束编译循环，原有快照复制、单个计划编译和最终权威校验仍有与监听器/标签规模相关的成本，不是硬实时上限。该版本不降低触发、周期校验或随机校验频率，不在异步线程读取游戏对象，也不引入运行时依赖。

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
