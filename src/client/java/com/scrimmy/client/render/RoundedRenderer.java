package com.scrimmy.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.setting.GradientSetting;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

public final class RoundedRenderer {
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1)
        .add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline SOLID = pipeline(false);
    private static final RenderPipeline GLASS = pipeline(true);

    private static final List<Shape> SHAPES = new ArrayList<>();
    private static int used;
    private static ScreenRectangle viewport;
    public static void beginFrame() { used = 0; }
    private static Shape next(GuiGraphics g) {
        if (viewport == null || viewport.width() != g.guiWidth() || viewport.height() != g.guiHeight())
            viewport = new ScreenRectangle(0, 0, g.guiWidth(), g.guiHeight());
        if (used == SHAPES.size()) SHAPES.add(new Shape());
        Shape shape = SHAPES.get(used++);
        shape.pose.set(g.pose()); shape.scissorArea = g.scissorStack.peek(); shape.bounds = viewport;
        return shape;
    }
    private RoundedRenderer() {}

    /** Reusable glass-style panel for moving world labels. Its owner keeps one instance per marker. */
    public static final class ReusablePanel {
        private final PanelShape shadow = new PanelShape(2), fill = new PanelShape(0), border = new PanelShape(1);
        private final PanelShape glass = new PanelShape(0);
        public void draw(GuiGraphics g, int width, int height, int radius, int color, int outline, int shadowColor, ScreenRectangle viewport) {
            draw(g, width, height, radius, color, outline, shadowColor, viewport, null);
        }
        public void draw(GuiGraphics g, int width, int height, int radius, int color, int outline, int shadowColor, ScreenRectangle viewport, TextureSetup texture) {
            shadow.update(g, width, height, radius, shadowColor, viewport);
            if (texture != null) {
                glass.texture = texture;
                glass.update(g, width, height, radius, ((int)((color >>> 24) * ThemeSurfaces.glass()) << 24) | 0xffffff, viewport);
            }
            fill.update(g, width, height, radius, color, viewport);
            border.update(g, width, height, radius, outline, viewport);
        }
        private static final class PanelShape implements GuiElementRenderState {
            private final int kind;
            private final Matrix3x2f pose = new Matrix3x2f();
            private int width, height, radius, color;
            private ScreenRectangle viewport;
            private TextureSetup texture = TextureSetup.noTexture();
            private PanelShape(int kind) { this.kind = kind; }
            void update(GuiGraphics g, int width, int height, int radius, int color, ScreenRectangle viewport) {
                this.width = width; this.height = height; this.radius = radius; this.color = color; this.viewport = viewport;
                pose.set(g.pose());
                if (kind == 2) pose.translate(0, 2);
                g.guiRenderState.submitGuiElement(this);
            }
            @Override public RenderPipeline pipeline() { return texture == TextureSetup.noTexture() ? SOLID : GLASS; }
            @Override public TextureSetup textureSetup() { return texture; }
            @Override public ScreenRectangle scissorArea() { return viewport; }
            // A conservative cached viewport avoids constructing a moving rectangle each frame.
            @Override public ScreenRectangle bounds() { return viewport; }
            @Override public void buildVertices(VertexConsumer vertices) {
                int pad = kind == 2 ? 8 : 1;
                vertex(vertices, -width / 2f - pad, -pad);
                vertex(vertices, -width / 2f - pad, height + pad);
                vertex(vertices, width / 2f + pad, height + pad);
                vertex(vertices, width / 2f + pad, -pad);
            }
            private void vertex(VertexConsumer vertices, float x, float y) {
                vertices.addVertexWith2DPose(pose, x, y).setColor(color).setUv(x, y - height / 2f)
                    .setUv1(width, height).setUv2(radius * 16, kind);
            }
        }
    }

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
        for (int i = 0; i <= stops.size(); i++) {
            float left = i == 0 ? x - 1 : x + (float)stops.get(i - 1).position() * w;
            float right = i == stops.size() ? x + w + 1 : x + (float)stops.get(i).position() * w;
            if (right <= left) continue;
            int a = stops.get(Math.max(0, i - 1)).color(), b = stops.get(Math.min(stops.size() - 1, i)).color();
            a = ((int)((a >>> 24) * opacity) << 24) | (a & 0xffffff);
            b = ((int)((b >>> 24) * opacity) << 24) | (b & 0xffffff);
            Shape shape = next(g);
            shape.set(TextureSetup.noTexture(), x, y, w, h, Math.min(radius, Math.min(w, h) / 2), a, 0, 1, left, right, b);
            g.guiRenderState.submitGuiElement(shape);
        }
    }

    private static void submit(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int kind, TextureSetup texture) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        ScreenRectangle clip = g.scissorStack.peek();
        if (clip != null && (clip.width() <= 0 || clip.height() <= 0)) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        int padding = kind == 2 ? 8 : 1;
        Shape shape = next(g);
        shape.set(texture, x, y, w, h, r, color, kind, padding, x - padding, x + w + padding, color);
        g.guiRenderState.submitGuiElement(shape);
    }

    /** Retained until frame consumption. Conservative viewport bounds require no moving rectangles. */
    private static final class Shape implements GuiElementRenderState {
        private final Matrix3x2f pose = new Matrix3x2f();
        private TextureSetup textureSetup;
        private int x, y, width, height, radius, color, kind, padding, endColor;
        private float left, right;
        private ScreenRectangle scissorArea, bounds;
        void set(TextureSetup texture, int x, int y, int width, int height, int radius, int color,
                 int kind, int padding, float left, float right, int endColor) {
            this.textureSetup = texture; this.x = x; this.y = y; this.width = width; this.height = height;
            this.radius = radius; this.color = color; this.kind = kind; this.padding = padding;
            this.left = left; this.right = right; this.endColor = endColor;
        }
        @Override public RenderPipeline pipeline() { return textureSetup == TextureSetup.noTexture() ? SOLID : GLASS; }
        @Override public TextureSetup textureSetup() { return textureSetup; }
        @Override public ScreenRectangle scissorArea() { return scissorArea; }
        @Override public ScreenRectangle bounds() { return bounds; }
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
