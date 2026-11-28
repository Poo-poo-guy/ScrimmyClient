package com.scrimmy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scrimmy.client.typing.TextAnimator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiLineEditBox.class)
abstract class MultiLineEditBoxMixin extends AbstractTextAreaWidget {
    @Shadow @Final private Font font;
    @Shadow @Final private MultilineTextField textField;
    @Shadow @Final private int cursorColor;
    @Shadow @Final private boolean textShadow;
    @Unique private TextAnimator.State scrimmy$animation;
    @Unique private String scrimmy$label;

    protected MultiLineEditBoxMixin(int x, int y, int width, int height, Component message) { super(x, y, width, height, message); }

    @Inject(method = "renderContents", at = @At("HEAD"))
    private void scrimmy$begin(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (scrimmy$label == null) scrimmy$label = getMessage().getString();
        scrimmy$animation = font.isBidirectional() ? null : TextAnimator.begin(this, 0, textField.value(), false, isFocused(), scrimmy$label);
    }

    // The first three String draw sites are page text; the fourth is the append caret.
    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 0))
    private void scrimmy$prefix(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        int start = ((TextLineViewAccessor)(Object)textField.getLineView((y - getInnerTop()) / font.lineHeight)).scrimmy$beginIndex();
        if (!TextAnimator.draw(scrimmy$animation, g, font, text, start, x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 1))
    private void scrimmy$suffix(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.draw(scrimmy$animation, g, font, text, textField.cursor(), x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 2))
    private void scrimmy$line(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        int start = ((TextLineViewAccessor)(Object)textField.getLineView((y - getInnerTop()) / font.lineHeight)).scrimmy$beginIndex();
        if (!TextAnimator.draw(scrimmy$animation, g, font, text, start, x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 3))
    private void scrimmy$append(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$animation)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void scrimmy$caret(GuiGraphics g, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$animation)) original.call(g, x1, y1, x2, y2, color);
    }

    @WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;textHighlight(IIIIZ)V"))
    private void scrimmy$selection(GuiGraphics g, int x1, int y1, int x2, int y2, boolean invert, Operation<Void> original) {
        if (!TextAnimator.selection(scrimmy$animation, g, x1, y1, x2, y2, invert)) original.call(g, x1, y1, x2, y2, invert);
    }

    @Inject(method = "renderContents", at = @At("TAIL"))
    private void scrimmy$finish(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (scrimmy$animation == null) return;
        int line = textField.getLineAtCursor();
        int start = ((TextLineViewAccessor)(Object)textField.getLineView(line)).scrimmy$beginIndex();
        int y = getInnerTop() + line * font.lineHeight;
        float x = getInnerLeft() + TextAnimator.width(scrimmy$animation, font, start, textField.cursor());
        TextAnimator.finish(scrimmy$animation, g);
        TextAnimator.caret(scrimmy$animation, g, font, x, y, cursorColor, textField.cursor() == textField.value().length(), textShadow,
                withinContentAreaTopBottom(y, y + font.lineHeight));
    }
}
