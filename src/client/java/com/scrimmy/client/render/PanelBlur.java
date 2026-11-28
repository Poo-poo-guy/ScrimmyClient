package com.scrimmy.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;

/** One scene capture / blur per frame, shared by every client surface. Targets survive style switches. */
public final class PanelBlur implements AutoCloseable {
    private static TextureTarget original, blurred;
    private static TextureSetup sharedTexture;
    private static long frame, captured = -1;
    private boolean requested;
    public static void beginFrame() { frame++; }

    /** HUD glass uses the theme strength without changing the user's menu blur preference. */
    public void capture(Minecraft client, int strength) {
        int effective = Math.round(strength * ThemeSurfaces.glass());
        if (effective < 1) { requested = false; return; }
        int previous = client.options.getMenuBackgroundBlurriness();
        try {
            if (previous != effective) client.options.menuBackgroundBlurriness().set(effective);
            capture(client);
        } finally {
            if (previous != effective) client.options.menuBackgroundBlurriness().set(previous);
        }
    }

    public void capture(Minecraft client) {
        if (ThemeSurfaces.glass() < .001f || client.options.getMenuBackgroundBlurriness() < 1) { requested = false; return; }
        requested = true;
        if (captured == frame) return;
        RenderTarget main = client.getMainRenderTarget();
        if (original == null || original.width != main.width || original.height != main.height) {
            release();
            original = new TextureTarget("Scrimmy scene", main.width, main.height, false);
            blurred = new TextureTarget("Scrimmy panel blur", main.width, main.height, false);
            sharedTexture = TextureSetup.singleTexture(blurred.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        copy(main, original);
        try {
            client.gameRenderer.processBlurEffect();
            copy(main, blurred);
            captured = frame;
        } finally { copy(original, main); }
    }

    private static void copy(RenderTarget from, RenderTarget to) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            from.getColorTexture(), to.getColorTexture(), 0, 0, 0, 0, 0, from.width, from.height);
    }
    public TextureSetup texture() { return requested && captured == frame ? sharedTexture : null; }
    @Override public void close() { requested = false; }
    /** Called on resource reload and client shutdown, on the render thread. */
    public static void release() {
        if (original != null) original.destroyBuffers();
        if (blurred != null) blurred.destroyBuffers();
        original = null; blurred = null; sharedTexture = null; captured = -1;
    }
}
