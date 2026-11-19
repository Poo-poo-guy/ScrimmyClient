package com.scrimmy.client.mixin;

import com.scrimmy.client.render.SdfFonts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Font.class)
abstract class FontSourceMixin {
    @Inject(method = "getGlyphSource", at = @At("HEAD"), cancellable = true)
    private void scrimmy$fontSource(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        GlyphSource source = SdfFonts.source(description);
        if (source != null) cir.setReturnValue(source);
    }
}
