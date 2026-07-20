# Mixin and hybrid-server compatibility

Four optional, exact-descriptor mixins are used:

| Target | Purpose | Failure behavior |
|---|---|---|
| `SimpleCriterionTrigger` listener methods | Mirror inventory trigger listener lifecycle | Replacement remains disabled |
| `InventoryChangeTrigger.trigger(ServerPlayer, Inventory, ItemStack)` | Run exact candidate path and cancel only on success | Original body executes |
| `ItemPredicate.test(ItemStack)` | Count predicate calls while a trigger scope is active | Metrics incomplete; replacement disabled by self-check |
| `MinecraftServer.reloadResources(Collection)` | Mark the asynchronous reload window | Replacement disabled by self-check |

The mixin config is non-required and every injection uses `require = 0`; a transformed-method inspection records which hooks were actually inserted. At `ServerStartedEvent`, exact Java descriptors and all hook markers are checked. Any missing or changed target disables cancellation and retains vanilla behavior.

No accessor targets `SimpleCriterionTrigger.players`; Paper/Youer may relocate or replace it without affecting the index. No overwrite, redirect, field shadow, Unsafe, Bukkit internals, or runtime classloader hack is used.

Known competing advancement optimizers (`achiopt`, `cerulean`, `icterine`) suppress these mixins through the early loading mod list. Unknown trigger-replacement mixins are not assumed compatible; shadow verification and the circuit breaker provide runtime protection, but operators should test their exact mixin set before production deployment.
