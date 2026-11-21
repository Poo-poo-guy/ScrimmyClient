package com.scrimmy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void scrimmy$uiFrame(CallbackInfo ci) {
        com.scrimmy.client.animation.AnimationClock.UI.frame(System.nanoTime());
        com.scrimmy.client.notification.NotificationRenderer.beginFrame();
        com.scrimmy.client.render.ClientFonts.frame();
        com.scrimmy.client.render.ThemeSurfaces.frame();
        com.scrimmy.client.render.PanelBlur.beginFrame();
        com.scrimmy.client.render.RoundedRenderer.beginFrame();
    }

    @Inject(method = {"bobHurt", "bobView"}, at = @At("HEAD"), cancellable = true)
    private void scrimmy$steadyCamera(PoseStack pose, float partial, CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module != null && module.active()) ci.cancel();
    }
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(FZLorg/joml/Matrix4f;)V"), index = 1)
    private boolean scrimmy$hideDetachedHands(boolean hidden) {
        Freecam module = Freecam.current();
        return hidden || (module != null && module.active());
    }
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void scrimmy$noDetachedTarget(float partial, CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module == null || !module.active()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.hitResult = null; mc.crosshairPickEntity = null;
        ci.cancel();
    }
}
