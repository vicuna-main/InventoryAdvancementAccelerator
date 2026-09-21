# Mixin and hybrid-server compatibility

Five optional, exact-descriptor lifecycle mixins are used, plus one guarded accessor:

| Target | Purpose | Failure behavior |
|---|---|---|
| `SimpleCriterionTrigger` listener methods | Mirror inventory trigger listener lifecycle | Replacement remains disabled |
| `InventoryChangeTrigger.trigger(ServerPlayer, Inventory, ItemStack)` | Run exact candidate path and cancel only on success | Original body executes |
| `ItemPredicate.test(ItemStack)` | Count predicate calls while a trigger scope is active | Metrics incomplete; replacement disabled by self-check |
| `MinecraftServer.reloadResources(Collection)` | Mark the asynchronous reload window | Replacement disabled by self-check |
| `PlayerAdvancements.registerListeners(ServerAdvancementManager)` | Queue one atomic, budgeted index warmup after bulk registration | Triggers stay vanilla until lazy warmup |

The mixin config is non-required and every injection uses `require = 0`; a transformed-method inspection records which hooks were actually inserted. At `ServerStartedEvent`, exact Java descriptors and all hook markers are checked. Any missing or changed target disables cancellation and retains vanilla behavior.

1.0.3 inspects each hook in its exact target method and descriptor, with a call owner equal to the transformed target class. All three listener lifecycle hooks, both trigger hooks and both reload hooks must be present; one surviving injection is insufficient. A later incomplete inspection clears a prior success flag. The bulk-registration hook remains optional because lazy warmup is available. These checks confirm this mod's observed injections; they do not certify arbitrary transformations applied later by another mod.

The guarded `SimpleCriterionTrigger.players` accessor is used only to copy the authoritative vanilla inventory-listener set into an unpublished builder. Startup verifies that the accessor applied; failure disables optimization before any trigger cancellation. No overwrite, redirect, field shadow, Unsafe, Bukkit internals, or runtime classloader hack is used.

During warmup, individual add/remove callbacks do not publish or rebuild a partial index. Completion reads the authoritative set again and only accepts an unchanged set or a strict subset with unchanged advancement/trigger identities. Pruning is confined to the completed unpublished builder. New or replaced listeners, duplicate logical keys, a registry-generation change, full registration, logout/removal of all listeners or reload retain the existing revalidation/cancellation safeguards. Snapshot comparisons cover the same `getContainerSize()` range as the vanilla matcher; custom mutable component values still depend on the platform's `ItemStack.matches`/copy semantics and need modpack testing.

Known competing advancement optimizers (`achiopt`, `cerulean`, `icterine`) suppress these mixins through the early loading mod list. Unknown trigger-replacement mixins are not assumed compatible; shadow verification and the circuit breaker provide runtime protection, but operators should test their exact mixin set before production deployment.
