package dev.invadvopt.mixin;

import dev.invadvopt.InvAdvOpt;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerAdvancements.class, priority = 900)
abstract class PlayerAdvancementsMixin {
    @Inject(
            method = "registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V",
            at = @At("RETURN"),
            require = 0)
    private void invadvopt$listenersRegistered(ServerAdvancementManager manager, CallbackInfo callback) {
        InvAdvOpt.RUNTIME.listenersRegistered((PlayerAdvancements)(Object)this);
    }
}
