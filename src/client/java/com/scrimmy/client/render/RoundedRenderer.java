package com.scrimmy.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.setting.GradientSetting;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

public final class RoundedRenderer {
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1)
        .add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline SOLID = pipeline(false);
    private static final RenderPipeline GLASS = pipeline(true);

    private RoundedRenderer() {}

    private static RenderPipeline pipeline(boolean glass) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", glass ? "pipeline/glass" : "pipeline/rounded"))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/rounded"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/rounded"))
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withDepthWrite(false);
        if (glass) builder.withSampler("Sampler0").withShaderDefine("GLASS");
        return RenderPipelines.register(builder.build());
    }

    public static void panel(GuiGraphics g, int x, int y, int width, int height, int radius,
                             int fill, int border, float glow, float shadow) {
        submit(g, x, y + 2, width, height, radius, (int)(shadow * 100) << 24, 2, TextureSetup.noTexture());
        rounded(g, x, y, width, height, radius, fill);
        outline(g, x, y, width, height, radius, border);
        if (glow > 0) outline(g, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1),
            ((int)(glow * 38) << 24) | (border & 0xffffff));
    }

    public static void rounded(GuiGraphics g, int x, int y, int width, int height, int radius, int color) {
        submit(g, x, y, width, height, radius, color, 0, TextureSetup.noTexture());
    }

    public static void outline(GuiGraphics g, int x, int y, int width, int height, int radius, int color) {
        submit(g, x, y, width, height, radius, color, 1, TextureSetup.noTexture());
    }

    public static void glass(GuiGraphics g, int x, int y, int width, int height, int radius, int color, TextureSetup texture) {
        submit(g, x, y, width, height, radius, color, 0, texture);
    }

    /** Each gradient segment shares the full bar's SDF coordinates, avoiding seams at stops. */
    public static void gradient(GuiGraphics g, int x, int y, int w, int h, int radius, List<GradientSetting.Stop> stops, float opacity) {
        if (w <= 0 || h <= 0) return;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle clip = g.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(x - 1, y - 1, w + 2, h + 2).transformMaxBounds(pose);
        if (clip != null) bounds = bounds.intersection(clip);
        if (bounds == null) return;
        for (int i = 0; i <= stops.size(); i++) {
            float left = i == 0 ? x - 1 : x + (float)stops.get(i - 1).position() * w;
            float right = i == stops.size() ? x + w + 1 : x + (float)stops.get(i).position() * w;
            if (right <= left) continue;
            int a = stops.get(Math.max(0, i - 1)).color(), b = stops.get(Math.min(stops.size() - 1, i)).color();
            a = ((int)((a >>> 24) * opacity) << 24) | (a & 0xffffff);
            b = ((int)((b >>> 24) * opacity) << 24) | (b & 0xffffff);
            g.guiRenderState.submitGuiElement(new Shape(SOLID, TextureSetup.noTexture(), pose,
                x, y, w, h, Math.min(radius, Math.min(w, h) / 2), a, 0, 1, left, right, b, clip, bounds));
        }
    }

    private static void submit(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int kind, TextureSetup texture) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        int padding = kind == 2 ? 8 : 1;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle clip = g.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(x - padding, y - padding, w + padding * 2, h + padding * 2).transformMaxBounds(pose);
        if (clip != null) bounds = bounds.intersection(clip);
        if (bounds == null) return;
        g.guiRenderState.submitGuiElement(new Shape(texture == TextureSetup.noTexture() ? SOLID : GLASS,
            texture, pose, x, y, w, h, r, color, kind, padding, x - padding, x + w + padding, color, clip, bounds));
    }

    private record Shape(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose,
                         int x, int y, int width, int height, int radius, int color, int kind, int padding,
                         float left, float right, int endColor,
                         ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
        @Override public void buildVertices(VertexConsumer vertices) {
            vertex(vertices, left, y - padding);
            vertex(vertices, left, y + height + padding);
            vertex(vertices, right, y + height + padding);
            vertex(vertices, right, y - padding);
        }
        private void vertex(VertexConsumer vertices, float px, float py) {
            // UV0 carries local SDF coordinates; integer attributes carry per-shape constants.
            vertices.addVertexWith2DPose(pose, px, py).setColor(px == left ? color : endColor)
                .setUv(px - x - width * 0.5f, py - y - height * 0.5f)
                .setUv1(width, height).setUv2(radius * 16, kind);
        }
    }
}
