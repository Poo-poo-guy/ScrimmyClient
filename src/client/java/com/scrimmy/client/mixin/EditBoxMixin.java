package com.scrimmy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scrimmy.client.typing.TextAnimator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditBox.class)
abstract class EditBoxMixin {
    @Shadow @Final private Font font;
    @Shadow private String value;
    @Shadow private int displayPos;
    @Shadow private int cursorPos;
    @Shadow private int maxLength;
    @Shadow private int textX;
    @Shadow private int textY;
    @Shadow private int textColor;
    @Shadow private int textColorUneditable;
    @Shadow private boolean isEditable;
    @Shadow private boolean textShadow;
    @Unique private TextAnimator.State scrimmy$animation;
    @Unique private String scrimmy$label;

    @Inject(method = "renderWidget", at = @At("HEAD"))
    private void scrimmy$begin(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        EditBox field = (EditBox)(Object)this;
        if (scrimmy$label == null) scrimmy$label = field.getMessage().getString();
        scrimmy$animation = font.isBidirectional() || !field.isVisible() ? null
                : TextAnimator.begin(this, 0, value, false, field.isFocused(), scrimmy$label);
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", ordinal = 0))
    private void scrimmy$prefix(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.draw(scrimmy$animation, g, font, text, displayPos, x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", ordinal = 1))
    private void scrimmy$suffix(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.draw(scrimmy$animation, g, font, text, cursorPos, x, y, color, shadow)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void scrimmy$caretBar(GuiGraphics g, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$animation)) original.call(g, x1, y1, x2, y2, color);
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", ordinal = 1))
    private void scrimmy$caretAppend(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
        if (!TextAnimator.smooth(scrimmy$animation)) original.call(g, font, text, x, y, color, shadow);
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;textHighlight(IIIIZ)V"))
    private void scrimmy$selection(GuiGraphics g, int x1, int y1, int x2, int y2, boolean invert, Operation<Void> original) {
        if (!TextAnimator.selection(scrimmy$animation, g, x1, y1, x2, y2, invert)) original.call(g, x1, y1, x2, y2, invert);
    }

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void scrimmy$finish(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (scrimmy$animation == null) return;
        EditBox field = (EditBox)(Object)this;
        float width = TextAnimator.width(scrimmy$animation, font, displayPos, cursorPos);
        boolean append = cursorPos == value.length() && value.length() < maxLength;
        float target = textX + (float)Math.ceil(width) + (append && !value.isEmpty() ? 1 : 0);
        g.enableScissor(textX, textY - 4, textX + field.getInnerWidth(), textY + font.lineHeight + 2);
        TextAnimator.finish(scrimmy$animation, g);
        TextAnimator.caret(scrimmy$animation, g, font, target, textY, isEditable ? textColor : textColorUneditable,
                append, textShadow, cursorPos >= displayPos && width <= field.getInnerWidth());
        g.disableScissor();
    }
}
