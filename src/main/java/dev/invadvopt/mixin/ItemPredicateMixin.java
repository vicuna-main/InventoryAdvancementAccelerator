package dev.invadvopt.mixin;

import dev.invadvopt.InvAdvOpt;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ItemPredicate.class, priority = 900)
abstract class ItemPredicateMixin {
    @Inject(method = "test(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), require = 0)
    private void invadvopt$countPredicateTest(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        InvAdvOpt.RUNTIME.onItemPredicateTest();
    }
}
