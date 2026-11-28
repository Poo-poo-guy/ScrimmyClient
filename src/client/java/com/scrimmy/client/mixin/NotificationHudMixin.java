package com.scrimmy.client.mixin;

import com.scrimmy.client.notification.NotificationRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class NotificationHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void scrimmy$notifications(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        // A screen is drawn after the HUD; let its hook own the frame in that case.
        if (Minecraft.getInstance().screen == null) NotificationRenderer.render(graphics);
    }
}
