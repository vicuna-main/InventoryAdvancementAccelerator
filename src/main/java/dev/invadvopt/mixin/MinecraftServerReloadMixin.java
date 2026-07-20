package dev.invadvopt.mixin;

import dev.invadvopt.InvAdvOpt;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftServer.class, priority = 900)
abstract class MinecraftServerReloadMixin {
    @Inject(method = "reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 0)
    private void invadvopt$reloadStarted(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        InvAdvOpt.RUNTIME.reloadStarted();
    }

    @Inject(method = "reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 0)
    private void invadvopt$reloadFuture(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        InvAdvOpt.RUNTIME.reloadFuture(callback.getReturnValue());
    }
}
