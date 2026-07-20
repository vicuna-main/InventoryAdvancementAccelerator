# Inventory Advancement Accelerator

`invadvopt` is a dedicated-server NeoForge mod for Minecraft 1.21.1. It accelerates the hot `inventory_changed` advancement trigger without replacing Minecraft's predicate or award logic. Clients do not install it.

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.x (compiled against 21.1.230 with Mojang mappings)
- Java 21
- Dedicated server only; `displayTest = IGNORE_ALL_VERSION` permits vanilla clients
- Designed to fail safely on NeoForge/Bukkit/Paper hybrids such as Youer
- No saved-data format, player attachment, capability, network channel, or client class

`achiopt`, `cerulean`, and `icterine` are mutually exclusive. Their presence is checked before the replacement mixins are applied and again after mod loading. In a conflict, `invadvopt` logs an error and leaves the vanilla trigger active.

## EXACT algorithm

Listener lifecycle hooks mirror only `InventoryChangeTrigger` registrations. Every `PlayerAdvancements` receives an independent identity index containing all listeners, compiled plans, direct/tag-expanded raw item IDs, wildcard/always/slot-sensitive buckets, listener and tag generations, and a 36-slot main-inventory snapshot.

For each trigger on the server thread:

1. Full/empty/occupied counts are calculated once over the same Inventory slots as vanilla.
2. The 36 main slots are compared with `ItemStack.matches`, which covers item, count, and all Data Components. Only changed stacks are copied. Both old and new item IDs are retained.
3. Candidates are the identity union of always, wildcard, slot-sensitive, every changed old/new item ID, and the supplied changed stack's item ID.
4. Minecraft's own `TriggerInstance.matches(...)` runs for every candidate, followed in vanilla order by the original `ContextAwarePredicate`.
5. Matching listeners are collected first and only then run through `Listener.run(PlayerAdvancements)`. This preserves `PlayerAdvancements.award`, Bukkit/Paper advancement events, and the once-only criterion lifecycle.

First use, listener registration, login, reload, tag-generation change, and circuit-breaker reset force a complete listener pass. EXACT never skips ticks, ignores empty stacks, ignores decreases, filters only by count, collapses changes to the final stack, reads Inventory asynchronously, or caches final predicate results.

Tags are expanded only as a coarse index. `ItemPredicate.test` and every component/sub-predicate remain authoritative. Unknown/incomplete plans, reload windows, off-thread calls, reentrancy, missing hooks, or internal exceptions fall through to the original method.

## Verification and circuit breakers

The default 1% shadow sample evaluates optimized candidates and then evaluates only the omitted listeners to form a complete vanilla-equivalent identity set. Candidate listeners are not evaluated twice. Awards execute once from the selected result.

Every 200 ticks, the next trigger for that player is force-verified. `/invadvopt verify` does the same on demand. A mismatch uses the complete result for the current event, logs only advancement/criterion IDs, UUID, item registry ID, generations, and mismatch type, then disables that player's index. Three consecutive mismatches switch the process to `VANILLA`. Full NBT is never logged.

## Configuration

NeoForge creates `config/invadvopt-common.toml` with root keys:

```toml
mode = "EXACT"
enabled = true
shadowVerifyRate = 0.01
periodicFullScanTicks = 200
fallbackOnUnknownPredicate = true
fallbackOnOffThreadCall = true
disableOnMismatch = true
metricsEnabled = true
debugLogging = false
```

`AGGRESSIVE` is accepted for controlled experiments but currently uses the same no-skip engine as EXACT. It is not the default. Safety fallbacks for unknown predicates and off-thread calls remain mandatory in EXACT.

## Commands

All commands require permission level 4 (server operator):

- `/invadvopt status`
- `/invadvopt stats`
- `/invadvopt verify`
- `/invadvopt mode vanilla`
- `/invadvopt mode exact`
- `/invadvopt reset-stats`

Metrics include trigger count, raw/candidate listener totals, reduction, `ItemPredicate.test` calls, full scans, fallbacks by reason, mismatches, total/average/P95/max latency, and top player/item registry-ID hotspots.

## Build and test

```powershell
.\gradlew.bat test
.\gradlew.bat runGameTestServer --offline
.\gradlew.bat build
```

The randomized differential test models 5,000 listeners across 10,000 sequential inventory events, including first-pass registration and once-only award removal. GameTests cover count decrease, empty transitions, Data Component-only mutation, and authoritative multi-predicate/count matching. See [BENCHMARK.md](BENCHMARK.md) and [MIXIN_COMPATIBILITY.md](MIXIN_COMPATIBILITY.md).

## Operational rollback

Set `mode = "VANILLA"`, run `/invadvopt mode vanilla`, or remove the jar. No world or player data is written, so removal requires no migration.
