package dev.invadvopt.gametest;

import dev.invadvopt.InvAdvOpt;
import dev.invadvopt.index.InventorySnapshot;
import dev.invadvopt.index.PlanCompiler;
import dev.invadvopt.index.PlayerIndex;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
public final class InventoryOptimizationGameTests {
    private InventoryOptimizationGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void snapshotDetectsCountEmptyAndComponentChanges(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack stack = new ItemStack(Items.REDSTONE, 8);
        player.getInventory().setItem(0, stack);
        InventorySnapshot snapshot = new InventorySnapshot();
        Set<Integer> changed = new HashSet<>();
        helper.assertTrue(snapshot.updateAndCollectChanges(player.getInventory(), changed), "first snapshot must force a full scan");

        changed.clear();
        stack.setCount(7);
        helper.assertTrue(!snapshot.updateAndCollectChanges(player.getInventory(), changed) && !changed.isEmpty(), "count decrease was not detected");

        changed.clear();
        CompoundTag tag = new CompoundTag();
        tag.putInt("energy", 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        snapshot.updateAndCollectChanges(player.getInventory(), changed);
        helper.assertTrue(!changed.isEmpty(), "component-only mutation was not detected");

        changed.clear();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        snapshot.updateAndCollectChanges(player.getInventory(), changed);
        helper.assertTrue(!changed.isEmpty(), "transition to an empty stack was not detected");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void originalTriggerInstanceRemainsTheFinalMatcher(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemPredicate diamonds = new ItemPredicate(Optional.of(HolderSet.direct(Items.DIAMOND.builtInRegistryHolder())),
                MinMaxBounds.Ints.atLeast(2), DataComponentPredicate.EMPTY, Map.of());
        ItemPredicate apples = new ItemPredicate(Optional.of(HolderSet.direct(Items.APPLE.builtInRegistryHolder())),
                MinMaxBounds.Ints.ANY, DataComponentPredicate.EMPTY, Map.of());
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        player.getInventory().setItem(1, new ItemStack(Items.APPLE, 1));
        InventoryChangeTrigger.TriggerInstance instance = new InventoryChangeTrigger.TriggerInstance(Optional.empty(),
                InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of(diamonds, apples));
        helper.assertTrue(instance.matches(player.getInventory(), new ItemStack(Items.STICK), 0, 39, 2),
                "multi-predicate vanilla scan should match independent of changedStack");
        player.getInventory().getItem(0).setCount(1);
        helper.assertTrue(!instance.matches(player.getInventory(), new ItemStack(Items.STICK), 0, 39, 2),
                "vanilla count predicate must reject the changed inventory");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void newlyAddedListenerUsesPendingBucketWithoutFullScan(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.APPLE));
        PlayerIndex index = new PlayerIndex();
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> diamond = listener("diamond", Items.DIAMOND);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> apple = listener("apple", Items.APPLE);
        index.add(diamond, 0L);
        index.select(player.getInventory(), new ItemStack(Items.STICK), 0L, 0L, 0);

        index.add(apple, 0L);
        PlayerIndex.Selection selection = index.select(
                player.getInventory(), new ItemStack(Items.STICK), 0L, 1L, 0);

        helper.assertTrue(!selection.mandatoryFull(), "one registration invalidated the complete snapshot");
        helper.assertTrue(selection.candidates().size() == 1 && selection.candidates().getFirst() == apple,
                "the pending listener was not isolated to its first event");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mergedSnapshotReportsExactVanillaSlotCounts(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack fullStack = new ItemStack(Items.REDSTONE, Items.REDSTONE.getDefaultMaxStackSize());
        player.getInventory().setItem(0, fullStack);
        player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));

        PlayerIndex index = new PlayerIndex();
        PlayerIndex.Selection selection = index.select(
                player.getInventory(), fullStack, 0L, 0L, 0);

        int expectedSize = player.getInventory().getContainerSize();
        helper.assertTrue(selection.fullSlots() == 1, "merged snapshot lost a full stack");
        helper.assertTrue(selection.occupiedSlots() == 2, "merged snapshot reported the wrong occupied count");
        helper.assertTrue(selection.emptySlots() == expectedSize - 2, "merged snapshot reported the wrong empty count");
        helper.assertTrue(selection.fullSlots() + selection.emptySlots() <= expectedSize,
                "merged slot counts exceeded the inventory size");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void mandatoryFullSelectionDoesNotPolluteSteadyStateCandidates(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        PlayerIndex index = new PlayerIndex();
        for (int listener = 0; listener < 2_000; listener++) {
            index.add(listener("bulk_" + listener, Items.DIAMOND), 0L);
        }

        PlayerIndex.Selection first = index.select(
                player.getInventory(), new ItemStack(Items.STICK), 0L, 0L, 0);
        helper.assertTrue(first.mandatoryFull() && first.candidates().size() == 2_000,
                "initial listener snapshot was not complete");

        PlayerIndex.Selection steady = index.select(
                player.getInventory(), new ItemStack(Items.STICK), 0L, 1L, 0);
        helper.assertTrue(!steady.mandatoryFull() && steady.candidates().isEmpty(),
                "mandatory full selection contaminated the steady-state candidate set");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void equalRemovalResolvesCanonicalListener(GameTestHelper helper) {
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> registered = listener("canonical", Items.DIAMOND);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> equalRemoval = listener("canonical", Items.APPLE);
        PlayerIndex index = new PlayerIndex();
        index.add(registered, 0L);

        helper.assertTrue(index.remove(equalRemoval), "logical listener key did not resolve to its canonical identity");
        helper.assertTrue(index.listenerCount() == 0, "canonical listener remained after removal");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void repeatedRemoveMissesAreBenignAndConstantTime(GameTestHelper helper) {
        PlayerIndex index = new PlayerIndex();
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> first = listener("first", Items.DIAMOND);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> middle = listener("middle", Items.APPLE);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> last = listener("last", Items.STICK);
        index.add(first, 0L);
        index.add(middle, 0L);
        index.add(last, 0L);

        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> missing = listener("already_complete", Items.STONE);
        for (int attempt = 0; attempt < 1_000; attempt++) {
            helper.assertTrue(!index.remove(missing), "missing listener unexpectedly removed an indexed listener");
        }
        helper.assertTrue(index.fallbackReason() == null, "benign remove misses disabled the index");
        helper.assertTrue(index.listenerCount() == 3, "benign remove misses changed the listener set");

        helper.assertTrue(index.remove(middle), "middle listener removal failed");
        helper.assertTrue(index.remove(last), "swap-removed listener position was not updated");
        helper.assertTrue(index.remove(first) && index.listenerCount() == 0, "listener store did not drain cleanly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void privateBuilderPublishesOnlyACompleteAuthoritativeIndex(GameTestHelper helper) {
        PlanCompiler compiler = new PlanCompiler();
        InventoryChangeTrigger.TriggerInstance trigger = trigger(Items.DIAMOND);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> first = listener("builder_first", trigger);
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> second = listener("builder_second", trigger);
        PlayerIndex.Builder builder = PlayerIndex.builder(compiler, List.of(first, second), 7L);

        helper.assertTrue(builder.addNext() && !builder.complete(), "builder completed before consuming its source");
        boolean rejectedPartialPublish = false;
        try {
            builder.finish();
        } catch (IllegalStateException expected) {
            rejectedPartialPublish = true;
        }
        helper.assertTrue(rejectedPartialPublish, "partially built index was publishable");
        helper.assertTrue(builder.addNext() && builder.complete(), "builder did not consume its complete source");
        helper.assertTrue(builder.matches(List.of(second, first)), "authoritative listener comparison was order-sensitive");

        PlayerIndex index = builder.finish();
        helper.assertTrue(index.listenerCount() == 2, "published index lost listeners");
        PlanCompiler.CacheStats cache = compiler.cacheStats();
        helper.assertTrue(cache.misses() == 1L && cache.hits() == 1L && cache.size() == 1,
                "player-independent trigger plan was not reused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void privateBuilderRejectsChangedAuthoritativeListeners(GameTestHelper helper) {
        PlanCompiler compiler = new PlanCompiler();
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> original =
                listener("builder_changed", trigger(Items.DIAMOND));
        CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> replacement =
                listener("builder_changed", trigger(Items.APPLE));
        PlayerIndex.Builder builder = PlayerIndex.builder(compiler, List.of(original), 0L);
        builder.addNext();

        helper.assertTrue(!builder.matches(List.of(replacement)),
                "changed authoritative trigger was accepted by the private build");
        helper.succeed();
    }

    private static CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener(String path, Item item) {
        return listener(path, trigger(item));
    }

    private static InventoryChangeTrigger.TriggerInstance trigger(Item item) {
        ItemPredicate predicate = new ItemPredicate(Optional.of(HolderSet.direct(item.builtInRegistryHolder())),
                MinMaxBounds.Ints.ANY, DataComponentPredicate.EMPTY, Map.of());
        return new InventoryChangeTrigger.TriggerInstance(
                Optional.empty(), InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of(predicate));
    }

    private static CriterionTrigger.Listener<InventoryChangeTrigger.TriggerInstance> listener(
            String path, InventoryChangeTrigger.TriggerInstance trigger) {
        Advancement advancement = new Advancement(Optional.empty(), Optional.empty(), AdvancementRewards.EMPTY,
                Map.of(), AdvancementRequirements.EMPTY, false);
        AdvancementHolder holder = new AdvancementHolder(
                ResourceLocation.fromNamespaceAndPath(InvAdvOpt.MOD_ID, path), advancement);
        return new CriterionTrigger.Listener<>(trigger, holder, "criterion");
    }
}
