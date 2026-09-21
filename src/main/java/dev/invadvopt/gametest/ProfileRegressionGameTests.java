package dev.invadvopt.gametest;

import dev.invadvopt.InvAdvOpt;
import dev.invadvopt.index.InventorySnapshot;
import dev.invadvopt.index.PlanCompiler;
import dev.invadvopt.index.PlayerIndex;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(InvAdvOpt.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProfileRegressionGameTests {
    private ProfileRegressionGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void snapshotIncludesArmorAndOffhand(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        InventorySnapshot snapshot = new InventorySnapshot();
        Set<Integer> changed = new HashSet<>();
        snapshot.updateAndCollectChanges(player.getInventory(), changed);

        player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
        snapshot.updateAndCollectChanges(player.getInventory(), changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.DIAMOND_BOOTS)),
                "armor changes were omitted even though the vanilla matcher scans those slots");

        changed.clear();
        player.getInventory().setItem(40, new ItemStack(Items.APPLE));
        snapshot.updateAndCollectChanges(player.getInventory(), changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.APPLE)),
                "offhand changes were omitted from the snapshot");

        changed.clear();
        player.getInventory().setItem(36, ItemStack.EMPTY);
        player.getInventory().setItem(40, ItemStack.EMPTY);
        snapshot.updateAndCollectChanges(player.getInventory(), changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.DIAMOND_BOOTS))
                        && changed.contains(BuiltInRegistries.ITEM.getId(Items.APPLE)),
                "old item identities were lost when equipment slots became empty");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void snapshotTracksComponentsAndInventoryResize(GameTestHelper helper) {
        ResizableInventory inventory = new ResizableInventory(helper.makeMockPlayer(GameType.CREATIVE), 48);
        inventory.values[47] = new ItemStack(Items.DIAMOND_BOOTS);
        InventorySnapshot snapshot = new InventorySnapshot();
        Set<Integer> changed = new HashSet<>();
        snapshot.updateAndCollectChanges(inventory, changed);

        CompoundTag tag = new CompoundTag();
        tag.putInt("charge", 12);
        inventory.values[47].set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        snapshot.updateAndCollectChanges(inventory, changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.DIAMOND_BOOTS)),
                "component-only changes in extra slots were not tracked");

        changed.clear();
        inventory.resize(41);
        snapshot.updateAndCollectChanges(inventory, changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.DIAMOND_BOOTS)),
                "shrinking the inventory lost the old item identity");

        changed.clear();
        inventory.resize(60);
        inventory.values[59] = new ItemStack(Items.APPLE);
        snapshot.updateAndCollectChanges(inventory, changed);
        helper.assertTrue(changed.contains(BuiltInRegistries.ITEM.getId(Items.APPLE)),
                "growing the inventory lost the new item identity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void equipmentChangesSelectTheVanillaMultiItemMatch(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.APPLE));
        InventoryChangeTrigger.TriggerInstance trigger = new InventoryChangeTrigger.TriggerInstance(
                Optional.empty(), InventoryChangeTrigger.TriggerInstance.Slots.ANY,
                List.of(predicate(Items.APPLE), predicate(Items.DIAMOND_BOOTS)));
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener = listener("equipment", trigger);
        PlayerIndex index = new PlayerIndex();
        index.add(listener, 0L);
        index.select(player.getInventory(), new ItemStack(Items.STICK), 0L, 0L, 0);

        player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
        PlayerIndex.Selection selection = index.select(player.getInventory(), new ItemStack(Items.STICK), 0L, 1L, 0);
        helper.assertTrue(trigger.matches(player.getInventory(), new ItemStack(Items.STICK),
                        selection.fullSlots(), selection.emptySlots(), selection.occupiedSlots()),
                "the vanilla matcher did not match the test inventory");
        helper.assertTrue(!selection.mandatoryFull() && selection.candidates().contains(listener),
                "optimized selection omitted a matching equipment-dependent criterion");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void warmupRetainsWorkAcrossRemovalChurn(GameTestHelper helper) {
        PlanCompiler compiler = new PlanCompiler();
        InventoryChangeTrigger.TriggerInstance trigger = trigger(Items.DIAMOND);
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source = new ArrayList<>();
        for (int i = 0; i < 512; i++) source.add(listener("churn_" + i, trigger));
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative = new ArrayList<>(source);
        PlayerIndex.Builder builder = PlayerIndex.builder(compiler, source, 3L);
        int consumed = 0;
        while (!builder.complete()) {
            for (int i = 0; i < 16 && builder.addNext(); i++) consumed++;
            // Includes removals from both already compiled and not-yet-compiled entries.
            authoritative.remove(authoritative.size() / 2);
        }
        helper.assertTrue(builder.retainAuthoritative(authoritative), "removal-only churn required a rebuild");
        helper.assertTrue(builder.matches(authoritative), "pruned build did not equal the authoritative set");
        helper.assertTrue(builder.finish().listenerCount() == 480 && builder.removedListeners() == 32,
                "removed listeners survived publication");
        helper.assertTrue(consumed == 512 && compiler.cacheStats().misses() == 1 && compiler.cacheStats().hits() == 511,
                "surviving listeners were recompiled during removal-only reconciliation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void removalChurnCompilesFewerEntriesThanRestartProtocol(GameTestHelper helper) {
        InventoryChangeTrigger.TriggerInstance trigger = trigger(Items.DIAMOND);
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source = new ArrayList<>();
        for (int i = 0; i < 512; i++) source.add(listener("protocol_" + i, trigger));
        long baseline = compileRemovalProtocol(source, true);
        long optimized = compileRemovalProtocol(source, false);
        helper.assertTrue(baseline == 977 && optimized == 512,
                "unexpected compilation counts for 512 listeners and 31 identical removal events");
        com.mojang.logging.LogUtils.getLogger().info(
                "[invadvopt-test] removal protocol: baselineBindings={}, optimizedBindings={}, listeners=512, removals=31; work counts only, not MSPT",
                baseline, optimized);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void warmupRejectsUnknownReplacedAndDuplicateAuthority(GameTestHelper helper) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> first = listener("first", trigger(Items.APPLE));
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> second = listener("second", trigger(Items.STICK));
        PlayerIndex.Builder builder = PlayerIndex.builder(new PlanCompiler(), List.of(first, second), 0L);
        while (builder.addNext()) { /* Consume this bounded two-entry source. */ }

        helper.assertTrue(!builder.retainAuthoritative(List.of(first, listener("new", trigger(Items.DIAMOND)))),
                "an unseen listener was accepted");
        helper.assertTrue(!builder.retainAuthoritative(List.of(listener("first", trigger(Items.DIAMOND)))),
                "a replacement trigger was accepted under an existing logical key");
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> duplicate =
                new CriterionTrigger.Listener<>(first.trigger(), first.advancement(), first.criterion());
        helper.assertTrue(!builder.retainAuthoritative(List.of(first, duplicate)),
                "duplicate logical listeners concealed a missing authoritative listener");
        helper.assertTrue(builder.matches(List.of(first, second)), "failed validation partially mutated the build");
        helper.assertTrue(builder.retainAuthoritative(List.of()) && builder.finish().listenerCount() == 0,
                "removing every listener did not publish an empty index");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void reconciliationCannotMutatePartialOrPublishedIndexes(GameTestHelper helper) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener = listener("guard", trigger(Items.APPLE));
        PlayerIndex.Builder builder = PlayerIndex.builder(new PlanCompiler(), List.of(listener), 0L);
        assertIllegalState(helper, () -> builder.retainAuthoritative(List.of()), "partial build was reconciled");
        builder.addNext();
        helper.assertTrue(builder.retainAuthoritative(List.of(listener)), "complete authority was rejected");
        PlayerIndex published = builder.finish();
        assertIllegalState(helper, () -> builder.retainAuthoritative(List.of()), "published index was mutated through its builder");
        helper.assertTrue(published.listenerCount() == 1, "published index lost its listener");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = "runtime")
    public static void runtimePreservesAwardsRevocationAndReloadFallback(GameTestHelper helper) {
        helper.assertTrue(InvAdvOpt.RUNTIME.status().contains("replacementHealthy=true"),
                "the real transformed classes failed the complete hook self-check");
        GameProfile profile = new GameProfile(UUID.randomUUID(), "invadvopt-test");
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(),
                new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
        player.getAdvancements().stopListening();
        InventoryChangeTrigger.TriggerInstance trigger = trigger(Items.DIAMOND);
        Advancement advancement = new Advancement(Optional.empty(), Optional.empty(), AdvancementRewards.Builder.experience(7).build(),
                Map.of("criterion", new Criterion<>(CriteriaTriggers.INVENTORY_CHANGED, trigger)),
                new AdvancementRequirements(List.of(List.of("criterion"))), false);
        AdvancementHolder holder = new AdvancementHolder(ResourceLocation.fromNamespaceAndPath(InvAdvOpt.MOD_ID, "runtime_reward"), advancement);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener = new CriterionTrigger.Listener<>(trigger, holder, "criterion");
        try {
            CriteriaTriggers.INVENTORY_CHANGED.addPlayerListener(player.getAdvancements(), listener);
            InvAdvOpt.RUNTIME.listenersRegistered(player.getAdvancements());
            InvAdvOpt.RUNTIME.processIndexWarmups(helper.getLevel().getServer());
            long fallbacks = InvAdvOpt.RUNTIME.stats().snapshot().fallbacks();
            long triggers = InvAdvOpt.RUNTIME.stats().snapshot().triggers();
            ItemStack diamond = new ItemStack(Items.DIAMOND);
            player.getInventory().setItem(0, diamond);
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), diamond);
            helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone() && player.totalExperience == 7,
                    "optimized matching did not grant the original advancement reward exactly once");
            helper.assertTrue(InvAdvOpt.RUNTIME.stats().snapshot().fallbacks() == fallbacks,
                    "the supposed optimized award ran through fallback instead");
            helper.assertTrue(InvAdvOpt.RUNTIME.stats().snapshot().triggers() == triggers + 1,
                    "the optimizer did not account for the supposedly optimized trigger");
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), diamond);
            helper.assertTrue(player.totalExperience == 7, "a completed criterion awarded its reward twice");

            player.getAdvancements().revoke(holder, "criterion");
            InvAdvOpt.RUNTIME.reloadStarted();
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), diamond);
            helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone() && player.totalExperience == 14,
                    "reload fallback lost a revoked-and-earned criterion or duplicated its reward");
            helper.assertTrue(InvAdvOpt.RUNTIME.stats().snapshot().fallbackReasons().getOrDefault("datapack_reload_in_progress", 0L) > 0,
                    "reload did not retain the vanilla trigger path");
            helper.assertTrue(InvAdvOpt.RUNTIME.stats().snapshot().mismatches() == 0, "runtime shadow verification reported a mismatch");
        } finally {
            InvAdvOpt.RUNTIME.reloadFuture(CompletableFuture.completedFuture(null));
            player.getAdvancements().stopListening();
        }
        helper.succeed();
    }

    private static ItemPredicate predicate(Item item) {
        return new ItemPredicate(Optional.of(HolderSet.direct(item.builtInRegistryHolder())),
                MinMaxBounds.Ints.ANY, DataComponentPredicate.EMPTY, Map.of());
    }

    private static long compileRemovalProtocol(
            List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> source, boolean restartOnCallback) {
        PlanCompiler compiler = new PlanCompiler();
        List<CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance>> authoritative = new ArrayList<>(source);
        PlayerIndex.Builder builder = PlayerIndex.builder(compiler, authoritative, 0L);
        for (int slice = 0; slice < 100; slice++) {
            for (int i = 0; i < 16 && builder.addNext(); i++) { /* Same compile batch as the runtime. */ }
            if (builder.complete()) {
                if (!builder.retainAuthoritative(authoritative) || builder.finish().listenerCount() != 481) {
                    throw new IllegalStateException("protocol did not publish the complete surviving authority");
                }
                PlanCompiler.CacheStats stats = compiler.cacheStats();
                return stats.hits() + stats.misses();
            }
            if (slice < 31) {
                authoritative.remove(authoritative.size() / 2);
                if (restartOnCallback) builder = PlayerIndex.builder(compiler, authoritative, 0L);
            }
        }
        throw new IllegalStateException("bounded protocol did not converge");
    }

    private static InventoryChangeTrigger.TriggerInstance trigger(Item item) {
        return new InventoryChangeTrigger.TriggerInstance(Optional.empty(),
                InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of(predicate(item)));
    }

    private static CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener(
            String name, InventoryChangeTrigger.TriggerInstance trigger) {
        Advancement advancement = new Advancement(Optional.empty(), Optional.empty(), AdvancementRewards.EMPTY,
                Map.of(), AdvancementRequirements.EMPTY, false);
        return new CriterionTrigger.Listener<>(trigger,
                new AdvancementHolder(ResourceLocation.fromNamespaceAndPath(InvAdvOpt.MOD_ID, name), advancement), "criterion");
    }

    private static void assertIllegalState(GameTestHelper helper, Runnable action, String message) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, message);
    }

    private static final class ResizableInventory extends Inventory {
        private ItemStack[] values;

        private ResizableInventory(Player player, int size) {
            super(player);
            values = new ItemStack[0];
            resize(size);
        }

        private void resize(int size) {
            int previousSize = values.length;
            values = Arrays.copyOf(values, size);
            if (size > previousSize) Arrays.fill(values, previousSize, size, ItemStack.EMPTY);
        }

        @Override
        public int getContainerSize() { return values.length; }

        @Override
        public ItemStack getItem(int slot) { return values[slot]; }
    }
}
