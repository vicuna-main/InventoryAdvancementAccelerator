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

The guarded `SimpleCriterionTrigger.players` accessor is used only to copy the authoritative vanilla inventory-listener set into an unpublished builder. Startup verifies that the accessor applied; failure disables optimization before any trigger cancellation. No overwrite, redirect, field shadow, Unsafe, Bukkit internals, or runtime classloader hack is used.

Known competing advancement optimizers (`achiopt`, `cerulean`, `icterine`) suppress these mixins through the early loading mod list. Unknown trigger-replacement mixins are not assumed compatible; shadow verification and the circuit breaker provide runtime protection, but operators should test their exact mixin set before production deployment.
