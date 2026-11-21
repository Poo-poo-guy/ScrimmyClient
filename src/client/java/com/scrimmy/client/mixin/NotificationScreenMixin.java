package com.scrimmy.client.mixin;

import com.scrimmy.client.notification.NotificationRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
abstract class NotificationScreenMixin {
    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("TAIL"))
    private void scrimmy$notifications(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        NotificationRenderer.render(graphics);
    }
}
