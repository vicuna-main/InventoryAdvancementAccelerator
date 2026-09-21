# Inventory Advancement Accelerator

`invadvopt` is a dedicated-server NeoForge mod for Minecraft 1.21.1. It accelerates the hot `inventory_changed` advancement trigger without replacing Minecraft's predicate or award logic. Clients do not install it.

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.x (compiled against 21.1.235 with Mojang mappings)
- Java 21
- Dedicated server only; `displayTest = IGNORE_ALL_VERSION` permits vanilla clients
- Designed to fail safely on NeoForge/Bukkit/Paper hybrids such as Youer
- No saved-data format, player attachment, capability, network channel, or client class

`achiopt`, `cerulean`, and `icterine` are mutually exclusive. Their presence is checked before the replacement mixins are applied and again after mod loading. In a conflict, `invadvopt` logs an error and leaves the vanilla trigger active.

## EXACT algorithm

Listener lifecycle hooks mirror only `InventoryChangeTrigger` registrations. Every `PlayerAdvancements` receives an independent identity index containing all listeners, compiled plans, direct/tag-expanded raw item IDs, wildcard/always/slot-sensitive buckets, listener and tag generations, and a snapshot of every slot exposed by `Inventory.getContainerSize()`.

Full listener registration never constructs the index one listener at a time. The vanilla listener set remains authoritative while a private index is warmed in bounded server-thread slices. Triggers use vanilla until the complete source set and registry generation are revalidated and the index is atomically published. Individual callbacks no longer discard an in-flight build. At publication, removed listeners are pruned; additions, changed advancement/trigger identities and duplicate logical keys reject the build before pruning and require a fresh source. Full registration, removal of all listeners, reload and disabling the optimizer still discard the private build. Trigger-instance plans are shared across players for one registry generation and discarded on reload.

For each trigger on the server thread:

1. Full/empty/occupied counts are calculated once over the same Inventory slots as vanilla.
2. All inventory slots, including armor and offhand, are compared with `ItemStack.matches`, which covers item, count, and Data Components according to the platform's equality semantics. Only changed stacks are copied. Both old and new item IDs are retained, including disappearing slots when a compatible custom inventory shrinks.
3. Candidates are the identity union of always, wildcard, slot-sensitive, every changed old/new item ID, and the supplied changed stack's item ID.
4. Minecraft's own `TriggerInstance.matches(...)` runs for every candidate, followed in vanilla order by the original `ContextAwarePredicate`.
5. Matching listeners are collected first and only then run through `Listener.run(PlayerAdvancements)`. This preserves `PlayerAdvancements.award`, Bukkit/Paper advancement events, and the once-only criterion lifecycle.

First use, login, reload, tag-generation change, and circuit-breaker reset force a complete listener pass. A newly registered listener is checked once through a dedicated pending bucket without invalidating the other listeners' snapshot. A plan which cannot be indexed safely is kept in the always-check bucket, so it remains exact without forcing the entire player index through vanilla. EXACT never skips ticks, ignores empty stacks, ignores decreases, filters only by count, collapses changes to the final stack, reads Inventory asynchronously, or caches final predicate results.

Tags are expanded only as a coarse index. `ItemPredicate.test` and every component/sub-predicate remain authoritative. Unknown plans remain in the always-check bucket. Reload windows, off-thread calls, reentrancy, missing hooks, a genuinely desynchronized index, or internal exceptions fall through to the original method.

## Verification and circuit breakers

The default 0.1% shadow sample evaluates optimized candidates and then evaluates only the omitted listeners to form a complete vanilla-equivalent result. Candidate listeners are not evaluated twice. Awards execute once from the selected result. Independent time-based verification still runs every 200 ticks, retaining a bounded ten-second verification interval for active players without paying for a full scan on roughly every hundred high-frequency equipment updates.

Every 200 ticks, the next trigger for that player is force-verified. `/invadvopt verify` does the same on demand. A mismatch uses the complete result for the current event, logs only advancement/criterion IDs, UUID, item registry ID, generations, and mismatch type, then disables that player's index. Three consecutive mismatches switch the process to `VANILLA`. Full NBT is never logged.

## Configuration

NeoForge creates `config/invadvopt-common.toml` with root keys:

```toml
mode = "EXACT"
enabled = true
shadowVerifyRate = 0.001
periodicFullScanTicks = 200
fallbackOnUnknownPredicate = true
fallbackOnOffThreadCall = true
disableOnMismatch = true
indexWarmupBudgetMicrosPerTick = 1000
metricsEnabled = true
debugLogging = false
```

`AGGRESSIVE` is accepted for controlled experiments but currently uses the same no-skip engine as EXACT. It is not the default. Off-thread calls and a genuinely desynchronized index still fall through before inventory scanning. `fallbackOnUnknownPredicate` is retained for configuration compatibility; unknown plans are now evaluated from the always-check bucket and do not require a global fallback.

Listener lifecycle lookup uses the stable advancement ID plus criterion name, never the listener's deep predicate hash. If no complete index is active, individual registration and removal callbacks deliberately avoid materializing partial state. A removal callback for an already-absent listener is counted as `remove_miss` only when a complete index is active.

## Commands

All commands require permission level 4 (server operator):

- `/invadvopt status`
- `/invadvopt stats`
- `/invadvopt verify`
- `/invadvopt mode vanilla`
- `/invadvopt mode exact`
- `/invadvopt reset-stats`

Metrics include trigger count, raw/candidate listener totals, reduction, `ItemPredicate.test` calls, full scans, fallbacks by reason, index conditions (`unsafe_plan` and `remove_miss`), mismatches, total/average/P95/max latency, and top player/item registry-ID hotspots. Predicate scopes and listener indexes are not maintained while the global mode is `VANILLA` or the optimizer is disabled.

Version 1.0.3 adds `warmup_started`, `warmup_completed`, `warmup_restarted_listeners`, `warmup_restarted_registry`, and `warmup_pruned_listeners` under `indexConditions`. Compare counter deltas over the same profiling interval to distinguish removal churn from real additions/replacements. The existing `warmup_listeners` counts consumed source entries, including entries later pruned.

## Build and test

```powershell
.\gradlew.bat test
.\gradlew.bat runGameTestServer --offline
.\gradlew.bat build
```

The randomized differential test models 5,000 listeners across 10,000 sequential inventory events, including first-pass registration and once-only award removal. GameTests cover count decrease, empty transitions, Data Component-only mutation, equipment/extra slots, inventory resizing, authoritative multi-predicate/count matching, warmup removal churn and rejection of stale authority. A server-player test exercises real trigger mixins, once-only XP rewards, criterion revocation and reload fallback. ASM tests reject incomplete or misplaced hook applications. See [BENCHMARK.md](BENCHMARK.md) and [MIXIN_COMPATIBILITY.md](MIXIN_COMPATIBILITY.md).

## Operational rollback

Set `mode = "VANILLA"`, run `/invadvopt mode vanilla`, or remove the jar. No world or player data is written, so removal requires no migration.
