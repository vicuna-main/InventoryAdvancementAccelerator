package dev.invadvopt.gametest;

import dev.invadvopt.InvAdvOpt;
import dev.invadvopt.index.InventorySnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
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
}
