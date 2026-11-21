package com.scrimmy.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;

/** Prepared outside rendering. Glyph geometry, matrices, texture bindings and tint adapter are retained. */
final class RetainedText {
    private final Glyph[] glyphs;
    private final Matrix4f pose = new Matrix4f();
    private final Tint tint = new Tint();
    private ScreenRectangle viewport;

    RetainedText(Font font, Component[] runs, float[] offsets) {
        var prepared = new ArrayList<Glyph>();
        Font.GlyphVisitor visitor = new Font.GlyphVisitor() {
            @Override public void acceptGlyph(TextRenderable.Styled glyph) { prepared.add(new Glyph(glyph)); }
            @Override public void acceptEffect(TextRenderable effect) { prepared.add(new Glyph(effect)); }
        };
        for (int i = 0; i < runs.length; i++)
            font.prepareText(runs[i].getVisualOrderText(), offsets[i], 0, -1, false, false, 0).visit(visitor);
        glyphs = prepared.toArray(Glyph[]::new);
    }
    void draw(GuiGraphics g, float x, float y, int color, ScreenRectangle viewport) {
        if ((color >>> 24) == 0) return;
        this.viewport = viewport;
        pose.identity().mul(g.pose()).translate(x, y, 0);
        tint.color = color;
        for (int i = 0; i < glyphs.length; i++) g.guiRenderState.submitGlyphToCurrentLayer(glyphs[i]);
    }
    private final class Glyph implements GuiElementRenderState {
        private final TextRenderable renderable;
        private final TextureSetup texture;
        Glyph(TextRenderable renderable) {
            this.renderable = renderable;
            texture = renderable.guiPipeline() == SdfFonts.PIPELINE ? SdfFonts.textureSetup(renderable.textureView())
                    : TextureSetup.singleTextureWithLightmap(renderable.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
        @Override public void buildVertices(VertexConsumer vertices) {
            tint.target = vertices;
            renderable.render(pose, tint, 15728880, true);
            tint.target = null;
        }
        @Override public RenderPipeline pipeline() { return renderable.guiPipeline(); }
        @Override public TextureSetup textureSetup() { return texture; }
        @Override public ScreenRectangle scissorArea() { return viewport; }
        @Override public ScreenRectangle bounds() { return null; } // Submitted directly to the established toast layer.
    }
    private static final class Tint implements VertexConsumer {
        VertexConsumer target;
        int color;
        @Override public VertexConsumer addVertex(float x, float y, float z) { target.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            target.setColor(r * ((color >>> 16) & 255) / 255, g * ((color >>> 8) & 255) / 255,
                    b * (color & 255) / 255, a * (color >>> 24) / 255);
            return this;
        }
        @Override public VertexConsumer setColor(int argb) { return setColor((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24); }
        @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { target.setLineWidth(width); return this; }
    }
}
