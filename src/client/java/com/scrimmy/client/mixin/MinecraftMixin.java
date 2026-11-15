package com.scrimmy.client.mixin;

import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void scrimmy$noDetachedAttack(CallbackInfoReturnable<Boolean> cir) {
        Freecam module = Freecam.current();
        if (module != null && module.active()) cir.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void scrimmy$noDetachedMining(boolean held, CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module != null && module.active()) ci.cancel();
    }
    @Inject(method = {"startUseItem", "pickBlock"}, at = @At("HEAD"), cancellable = true)
    private void scrimmy$noDetachedInteraction(CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module != null && module.active()) ci.cancel();
    }
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void scrimmy$consumeDetachedActions(CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module == null || !module.active()) return;
        var keys = Minecraft.getInstance().options;
        while (keys.keyDrop.consumeClick()) {}
        while (keys.keySwapOffhand.consumeClick()) {}
    }
}
