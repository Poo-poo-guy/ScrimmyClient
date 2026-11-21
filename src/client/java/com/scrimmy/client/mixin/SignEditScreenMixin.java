package com.scrimmy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scrimmy.client.typing.TextAnimator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignEditScreen.class)
abstract class SignEditScreenMixin extends Screen {
    @Shadow @Final protected SignBlockEntity sign;
    @Shadow @Final private String[] messages;
    @Shadow private SignText text;
    @Shadow private int line;
    @Shadow private TextFieldHelper signField;
    @Unique private final TextAnimator.State[] scrimmy$lines = new TextAnimator.State[4];

    protected SignEditScreenMixin(Component title) { super(title); }

    @Inject(method = "renderSignText", at = @At("HEAD"))
    private void scrimmy$begin(GuiGraphics g, CallbackInfo ci) {
        for (int i = 0; i < messages.length; i++) {
            scrimmy$lines[i] = font.isBidirectional() || messages[i] == null ? null
                    : TextAnimator.begin(this, i, messages[i], true, i == line, "sign");
        }
    }

    @WrapOperation(method = "renderSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 0))
    private void scrimmy$text(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        int index = (y + 4 * sign.getTextLineHeight() / 2) / sign.getTextLineHeight();
        if (!TextAnimator.draw(scrimmy$lines[index], g, font, text, 0, x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 1))
    private void scrimmy$append(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$lines[line])) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void scrimmy$caret(GuiGraphics g, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$lines[line])) original.call(g, x1, y1, x2, y2, color);
    }

    @WrapOperation(method = "renderSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;textHighlight(IIIIZ)V"))
    private void scrimmy$selection(GuiGraphics g, int x1, int y1, int x2, int y2, boolean invert, Operation<Void> original) {
        if (!TextAnimator.selection(scrimmy$lines[line], g, x1, y1, x2, y2, invert)) original.call(g, x1, y1, x2, y2, invert);
    }

    @Inject(method = "renderSignText", at = @At("TAIL"))
    private void scrimmy$finish(GuiGraphics g, CallbackInfo ci) {
        for (int i = 0; i < messages.length; i++) TextAnimator.finish(scrimmy$lines[i], g);
        TextAnimator.State state = scrimmy$lines[line];
        if (state == null) return;
        int cursor = Math.max(0, Math.min(signField.getCursorPos(), messages[line].length()));
        float x = (float)Math.ceil(TextAnimator.width(state, font, 0, cursor)) - font.width(messages[line]) / 2;
        int y = line * sign.getTextLineHeight() - 4 * sign.getTextLineHeight() / 2;
        int color = text.hasGlowingText() ? text.getColor().getTextColor() : AbstractSignRenderer.getDarkColor(text);
        TextAnimator.caret(state, g, font, x, y, color, cursor == messages[line].length(), false, signField.getCursorPos() >= 0);
    }
}
