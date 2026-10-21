package com.scrimmy.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;

/** Keeps the world sharp outside the SDF panel masks. Targets are reused until resize. */
public final class PanelBlur implements AutoCloseable {
    private TextureTarget original, blurred;
    private TextureSetup texture;

    public void capture(Minecraft client) {
        RenderTarget main = client.getMainRenderTarget();
        if (original == null || original.width != main.width || original.height != main.height) {
            close();
            original = new TextureTarget("Scrimmy scene", main.width, main.height, false);
            blurred = new TextureTarget("Scrimmy panel blur", main.width, main.height, false);
            texture = TextureSetup.singleTexture(blurred.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        copy(main, original);
        try {
            client.gameRenderer.processBlurEffect();
            copy(main, blurred);
        } finally { copy(original, main); }
    }

    private static void copy(RenderTarget from, RenderTarget to) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            from.getColorTexture(), to.getColorTexture(), 0, 0, 0, 0, 0, from.width, from.height);
    }

    public TextureSetup texture() { return texture; }

    @Override public void close() {
        if (original != null) original.destroyBuffers();
        if (blurred != null) blurred.destroyBuffers();
        original = null; blurred = null; texture = null;
    }
}
