package com.scrimmy.client.mixin;

import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void scrimmy$cameraLook(LocalPlayer player, double horizontal, double vertical) {
        Freecam module = Freecam.current();
        if (module != null && module.active()) {
            if (module.acceptsInput(Minecraft.getInstance())) module.camera.turn(horizontal, vertical);
        } else player.turn(horizontal, vertical);
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void scrimmy$cameraSpeed(long window, double horizontal, double vertical, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Freecam module = Freecam.current();
        if (module == null || window != mc.getWindow().handle() || !module.acceptsInput(mc)) return;
        module.scroll(horizontal, vertical);
        ci.cancel();
    }
}
