package com.scrimmy.client.mixin;

import com.scrimmy.client.render.SdfFonts;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's hard-coded NEAREST sampler is replaced only for Scrimmy's SDF pipeline. */
@Mixin(GlyphRenderState.class)
abstract class GlyphSamplerMixin {
    @Shadow @Final private TextRenderable renderable;
    @Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
    private void scrimmy$linearSampler(CallbackInfoReturnable<TextureSetup> cir) {
        if (renderable.guiPipeline() == SdfFonts.PIPELINE)
            cir.setReturnValue(SdfFonts.textureSetup(renderable.textureView()));
    }
}
