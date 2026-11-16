package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.BaseLikelihood;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import com.scrimmy.client.module.basefinding.Basefinding;
import com.scrimmy.client.setting.GradientSetting;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Cached chunk boxes, AA outlines/tracers, projected labels, and animated glass toasts. */
public final class ChunkFinderRenderer implements AutoCloseable {
    private static final VertexFormat FORMAT =
            VertexFormat.builder()
                    .add("Position", VertexFormatElement.POSITION)
                    .add("Color", VertexFormatElement.COLOR)
                    .add("UV0", VertexFormatElement.UV0)
                    .add("UV1", VertexFormatElement.UV1)
                    .add("UV2", VertexFormatElement.UV2)
                    .build();
    private static final RenderPipeline[] PIPELINES = pipelines();
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6},
        {3, 7}
    };
    private static final int[][] FACES = {
        {0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}
    };
    private final Basefinding module;
    private final Matrix4f view = new Matrix4f(),
            culling = new Matrix4f(),
            identity = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector4f viewport = new Vector4f();
    private final Vector3f zero = new Vector3f();
    private final long[] order = new long[1024];
    private final float[] corners = new float[24];
    private ByteBuffer bytes;
    private MappableRingBuffer gpu;
    private int vertices;
    private boolean throughWalls, dimOccluded;
    private float hiddenOpacity;
    private long lastFrame;

    private static RenderPipeline[] pipelines() {
        RenderPipeline[] result = new RenderPipeline[3];
        DepthTestFunction[] depth = {
            DepthTestFunction.LEQUAL_DEPTH_TEST,
            DepthTestFunction.NO_DEPTH_TEST,
            DepthTestFunction.GREATER_DEPTH_TEST
        };
        for (int i = 0; i < result.length; i++)
            result[i] =
                    RenderPipelines.register(
                            RenderPipeline.builder()
                                    .withLocation(
                                            Identifier.fromNamespaceAndPath(
                                                    "scrimmyclient", "pipeline/chunk_finder_" + i))
                                    .withVertexShader(
                                            Identifier.fromNamespaceAndPath(
                                                    "scrimmyclient", "core/block_esp"))
                                    .withFragmentShader(
                                            Identifier.fromNamespaceAndPath(
                                                    "scrimmyclient", "core/block_esp"))
                                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                                    .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS)
                                    .withCull(false)
                                    .withDepthWrite(false)
                                    .withDepthTestFunction(depth[i])
                                    .withBlend(BlendFunction.TRANSLUCENT)
                                    .build());
        return result;
    }

    private static final int MAX_CHUNKS = 256, MAX_GEODES = 256, MAX_CACHED = 1024;
    private static final double FADE_SECONDS = .4;
    private final Map<Long, Visual> chunkCache = new HashMap<>(), geodeCache = new HashMap<>();
    private Visual[] visuals = new Visual[0];
    private final Visual[] projected = new Visual[MAX_CHUNKS];
    private final float[] labelBounds = new float[MAX_CHUNKS * 4];
    private final Vector4f clip = new Vector4f();
    private final PanelBlur blur = new PanelBlur();
    private ScreenRectangle screen;
    private int screenWidth, screenHeight, labelCount;
    private float labelResponse = 1;
    private final RoundedRenderer.ReusablePanel toastPanel = new RoundedRenderer.ReusablePanel();
    private String toastText;
    private int toastWidth;
    private long toastTime;

    public ChunkFinderRenderer(Basefinding module) { this.module = module; }

    public void initialize() {
        // Reserve all CPU vertex storage before the first world frame.
        ensure(MAX_CACHED * 72 * FORMAT.getVertexSize());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "chunk_finder"),
                (graphics, delta) -> hud(graphics));
    }

    public void toast(String text) {
        toastText = text; toastTime = System.nanoTime();
        toastWidth = Minecraft.getInstance().font.width(text) + 20;
    }

    public void clear() {
        chunkCache.clear(); geodeCache.clear(); visuals = new Visual[0];
        labelCount = vertices = 0; lastFrame = 0; blur.close();
        toastText = null;
    }

    /** Cached strings, panels and marker arrays change only on ticks/settings changes. */
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        chunkCache.values().removeIf(v -> !v.wanted && v.alpha < .002);
        geodeCache.values().removeIf(v -> !v.wanted && v.alpha < .002);
        for (Visual v : chunkCache.values()) v.wanted = false;
        for (Visual v : geodeCache.values()) v.wanted = false;
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (screen == null || screenWidth != width || screenHeight != height) {
            screenWidth = width; screenHeight = height; screen = new ScreenRectangle(0, 0, width, height);
        }
        int count = 0;
        if (module.active() && module.highlightChunks.get()) for (var chunk : module.chunks()) {
            if (chunk.score() < module.threshold.get() || count++ >= MAX_CHUNKS) break;
            Visual v = chunkCache.get(chunk.key());
            if (v == null) {
                if (chunkCache.size() >= MAX_CACHED / 2) continue;
                v = new Visual(); chunkCache.put(chunk.key(), v);
            }
            v.x = chunk.x() * 16; v.z = chunk.z() * 16;
            v.targetY = mc.player.getY() - 1;
            if (v.alpha == 0) v.y = v.targetY;
            v.width = v.depth = 16; v.height = .2f;
            v.score = chunk.score(); v.primary = chunk.primary(); v.wanted = true;
            v.label = v.primary ? "Best estimate" : "Base chance";
            v.labelWidth = mc.font.width(v.label);
            String score = Math.round(chunk.score()) + "/100";
            if (!score.equals(v.text)) { v.text = score; v.textWidth = mc.font.width(score); }
        }
        count = 0;
        if (module.active() && module.showGeodes.get()) for (var geode : module.geodes()) {
            double dx = geode.x() - mc.player.getX(), dz = geode.z() - mc.player.getZ();
            double range = module.range.get() * 16;
            if (Math.abs(dx) > range || Math.abs(dz) > range || count >= MAX_GEODES) continue;
            count++;
            Visual v = geodeCache.get(geode.id());
            if (v == null) {
                if (geodeCache.size() >= MAX_CACHED / 2) continue;
                v = new Visual(); geodeCache.put(geode.id(), v);
            }
            v.x = geode.minX(); v.y = geode.minY(); v.z = geode.minZ();
            v.width = geode.maxX() - geode.minX(); v.height = geode.maxY() - geode.minY();
            v.depth = geode.maxZ() - geode.minZ();
            v.geode = true; v.score = BaseLikelihood.growth(geode.stats()) * 100; v.wanted = true;
        }
        List<Visual> all = new ArrayList<>(chunkCache.values()); all.addAll(geodeCache.values());
        visuals = all.toArray(Visual[]::new);
    }

    public void extract(WorldExtractionContext context) {
        vertices = labelCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || module.world() != mc.level
                || !mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 1.0 / 60 : Math.min(.25, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        double animation = ScrimmyClient.THEME.animationSpeed.get();
        labelResponse = (float)(1 - Math.exp(-dt * 10 * animation));
        view.set(context.viewMatrix()); culling.set(context.cullProjectionMatrix()).mul(view); frustum.set(culling);
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
        double range = module.range.get() * 16;
        double blend = 1 - Math.exp(-dt * 4 * animation / FADE_SECONDS);
        int count = 0;
        for (int i = 0; i < visuals.length; i++) {
            Visual v = visuals[i];
            if (!v.geode) v.y += (v.targetY - v.y) * labelResponse;
            v.tagVisibility += ((module.nametags.get() ? 1 : 0) - v.tagVisibility) * labelResponse;
            double dx = v.x + v.width * .5 - cx, dz = v.z + v.depth * .5 - cz;
            double dist = Math.hypot(dx, dz);
            boolean selected = v.geode ? module.showGeodes.get() : module.highlightChunks.get() && v.score >= module.threshold.get();
            boolean wanted = v.wanted && module.active() && selected && Math.abs(dx) <= range && Math.abs(dz) <= range;
            v.alpha += ((wanted ? 1 : 0) - v.alpha) * blend;
            if (v.alpha < .002 || !frustum.testAab((float)(v.x - cx), (float)(v.y - cy), (float)(v.z - cz),
                    (float)(v.x + v.width - cx), (float)(v.y + (v.geode ? v.height : 3) - cy), (float)(v.z + v.depth - cz))) continue;
            order[count++] = ((long)Float.floatToRawIntBits((float)dist) << 32) | i;
        }
        if (bytes == null) return;
        bytes.clear();
        Arrays.sort(order, 0, count);
        throughWalls = true; dimOccluded = false; hiddenOpacity = 1;
        int accent = ScrimmyClient.THEME.accent.get() | 0xff000000;
        for (int j = count - 1; j >= 0; j--) {
            Visual v = visuals[(int)order[j]];
            double distance = Float.intBitsToFloat((int)(order[j] >>> 32));
            // Fade only near the far edge; nearby scores remain legible.
            double opacity = v.alpha * Math.max(0, Math.min(1, (range * 1.42 - distance) / 48));
            int base = GradientSetting.mix(0xff536f9c, accent, v.score / 100);
            int color = alpha(base, opacity * (.35 + .65 * v.score / 100));
            float x = (float)(v.x - cx), y = (float)(v.y - cy), z = (float)(v.z - cz);
            for (int k = 0; k < 8; k++) {
                corners[k * 3] = x + ((k & 1) == 0 ? 0 : v.width);
                corners[k * 3 + 1] = y + ((k & 2) == 0 ? 0 : v.height);
                corners[k * 3 + 2] = z + ((k & 4) == 0 ? 0 : v.depth);
            }
            for (int[] face : FACES) for (int k : face)
                vertex(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2],
                        alpha(color, v.primary ? .27 : .10), 0, 0, 0, 0, 0);
            for (int[] edge : EDGES) {
                int a = edge[0] * 3, b = edge[1] * 3;
                line(corners[a], corners[a + 1], corners[a + 2], corners[b], corners[b + 1], corners[b + 2],
                        alpha(color, v.primary ? 1 : .55), v.primary ? 2.5f : 1.2f);
            }
            if (!v.geode && v.tagVisibility > .002 && labelCount < MAX_CHUNKS) {
                clip.set(x + 8, y + 3, z + 8, 1); culling.transform(clip);
                if (clip.w > .01 && Math.abs(clip.x) < clip.w && Math.abs(clip.y) < clip.w) {
                    v.screenX = clip.x / clip.w; v.screenY = clip.y / clip.w; v.labelAlpha = opacity * v.tagVisibility;
                    float target = (float)Math.max(.75, Math.min(1.2, 64 / Math.max(1, distance)));
                    if (v.primary) target *= 1.12f;
                    v.labelScale += (target - v.labelScale) * labelResponse;
                    projected[labelCount++] = v;
                }
            }
        }
        bytes.flip();
    }

    private void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || mc.level != module.world()) return;
        drawToast(g, mc);
        if (labelCount == 0) return;
        // One shared blur capture for every pill; reuse the client's existing blur and SDF panels.
        if (ScrimmyClient.THEME.blur.get() >= 1 && mc.screen == null)
            blur.capture(mc, ScrimmyClient.THEME.blur.get().intValue());
        else blur.close();
        int placed = 0, accent = ScrimmyClient.THEME.accent.get() | 0xff000000;
        // Primary is placed first, then nearer secondary labels. No marker is moved off its chunk.
        for (int pass = 0; pass < 2; pass++) for (int i = labelCount - 1; i >= 0; i--) {
            Visual v = projected[i];
            if (v.primary != (pass == 0) || v.labelAlpha < .01) continue;
            float x = (v.screenX + 1) * screenWidth / 2f, y = (1 - v.screenY) * screenHeight / 2f;
            int width = Math.max(v.labelWidth, v.textWidth) + 16;
            float half = width * v.labelScale / 2, height = 30 * v.labelScale;
            boolean overlap = false;
            for (int j = 0; j < placed; j++) {
                int b = j * 4;
                if (x + half >= labelBounds[b] && x - half <= labelBounds[b + 2]
                        && y + height >= labelBounds[b + 1] && y <= labelBounds[b + 3]) { overlap = true; break; }
            }
            if (overlap || x - half < 0 || x + half > screenWidth || y < 0 || y + height > screenHeight) continue;
            int b = placed++ * 4;
            labelBounds[b] = x - half; labelBounds[b + 1] = y;
            labelBounds[b + 2] = x + half; labelBounds[b + 3] = y + height;
            g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(v.labelScale, v.labelScale);
            int scoreColor = GradientSetting.mix(0xff819ab7, accent, v.score / 100);
            int tint = ScrimmyClient.THEME.glassTint.get();
            int background = (tint & 0xffffff) | ((int)(ScrimmyClient.THEME.opacity.get() * 255) << 24);
            v.panel.draw(g, width, 30, Math.min(15, ScrimmyClient.THEME.radius.get().intValue() + 4),
                    alpha(background, v.labelAlpha),
                    alpha(accent, v.labelAlpha * (v.primary ? .6 : .25)),
                    alpha(v.primary ? (accent & 0xffffff) | 0x44000000 : 0x40000000, v.labelAlpha),
                    screen, blur.texture());
            g.drawString(mc.font, v.label, -v.labelWidth / 2, 4,
                    alpha(0xffc4d3e3, v.labelAlpha), false);
            g.drawString(mc.font, v.text, -v.textWidth / 2, 17, alpha(scoreColor, v.labelAlpha), false);
            g.pose().popMatrix();
        }
    }

    private void drawToast(GuiGraphics g, Minecraft mc) {
        if (toastText == null || !module.active() || screen == null) return;
        double age = (System.nanoTime() - toastTime) / 1_000_000_000.0;
        if (age > 5) { toastText = null; return; }
        double opacity = Math.min(1, age / .3) * Math.min(1, (5 - age) / .5);
        int width = Math.min(toastWidth, screenWidth - 16);
        float x = screenWidth - width / 2f - 8 + (float)((1 - opacity) * (width + 8));
        g.pose().pushMatrix(); g.pose().translate(x, 12);
        toastPanel.draw(g, width, 34, 8, alpha(0xe0152231, opacity),
                alpha(ScrimmyClient.THEME.accent.get(), opacity * .5), alpha(0x40000000, opacity), screen);
        g.drawString(mc.font, "Chunk Finder", -width / 2 + 10, 5, alpha(ScrimmyClient.THEME.accent.get(), opacity), false);
        g.drawString(mc.font, toastText, -width / 2 + 10, 20, alpha(0xffedf6ff, opacity), false);
        g.pose().popMatrix();
    }

    private static final class Visual {
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
        String text, label;
        int textWidth, labelWidth;
        boolean wanted, geode, primary;
        double x, y, z, targetY, score, alpha, labelAlpha, tagVisibility;
        float width, height, depth, screenX, screenY, labelScale = .75f;
    }

    private void ensure(int required) {
        if (bytes != null && bytes.capacity() >= required) {
            bytes.clear();
            return;
        }
        if (bytes != null) MemoryUtil.memFree(bytes);
        bytes =
                MemoryUtil.memAlloc(Math.max(65536, Integer.highestOneBit(required - 1) << 1))
                        .order(ByteOrder.nativeOrder());
    }

    private void line(
            float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        // Clip the ribbon's center line before projection, including crosshair tracers.
        float za = view.m02() * ax + view.m12() * ay + view.m22() * az + view.m32();
        float zb = view.m02() * bx + view.m12() * by + view.m22() * bz + view.m32();
        if (za > -.06f && zb > -.06f) return;
        if (za > -.06f) {
            float t = (-.06f - za) / (zb - za);
            ax += (bx - ax) * t;
            ay += (by - ay) * t;
            az += (bz - az) * t;
        } else if (zb > -.06f) {
            float t = (-.06f - zb) / (za - zb);
            bx += (ax - bx) * t;
            by += (ay - by) * t;
            bz += (az - bz) * t;
        }
        vertex(ax, ay, az, color, bx, by, bz, -1000, width);
        vertex(ax, ay, az, color, bx, by, bz, 1000, width);
        vertex(bx, by, bz, color, ax, ay, az, -1000, -width);
        vertex(bx, by, bz, color, ax, ay, az, 1000, -width);
    }

    private void vertex(
            float x,
            float y,
            float z,
            int color,
            float otherX,
            float otherY,
            float otherZ,
            int side,
            float width) {
        bytes.putFloat(x).putFloat(y).putFloat(z);
        bytes.put((byte) (color >> 16))
                .put((byte) (color >> 8))
                .put((byte) color)
                .put((byte) (color >>> 24));
        bytes.putFloat(otherX).putFloat(otherY);
        int bits = Float.floatToRawIntBits(otherZ);
        bytes.putShort((short) bits).putShort((short) (bits >>> 16));
        bytes.putShort((short) side).putShort((short) (width * 100));
        vertices++;
    }

    public void draw(WorldRenderContext context) {
        if (vertices == 0) return;
        if (gpu == null || gpu.size() < bytes.remaining()) {
            if (gpu != null) gpu.close();
            gpu =
                    new MappableRingBuffer(
                            () -> "Scrimmy ChunkFinder vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                            bytes.capacity());
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mapped =
                encoder.mapBuffer(gpu.currentBuffer().slice(0, bytes.remaining()), false, true)) {
            mapped.data().put(bytes);
            bytes.rewind();
        }
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(vertices / 4 * 6);
        var target = Minecraft.getInstance().getMainRenderTarget();
        viewport.set(target.width, target.height, 1, 1);
        var normal =
                RenderSystem.getDynamicUniforms().writeTransform(view, viewport, zero, identity);
        viewport.z = hiddenOpacity;
        var hidden =
                RenderSystem.getDynamicUniforms().writeTransform(view, viewport, zero, identity);
        try (var pass =
                encoder.createRenderPass(
                        () -> "Scrimmy ChunkFinder",
                        target.getColorTextureView(),
                        OptionalInt.empty(),
                        target.getDepthTextureView(),
                        OptionalDouble.empty())) {
            pass.setPipeline(PIPELINES[throughWalls && !dimOccluded ? 1 : 0]);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setVertexBuffer(0, gpu.currentBuffer());
            pass.setIndexBuffer(indices, sequential.type());
            if (throughWalls && dimOccluded) {
                pass.setPipeline(PIPELINES[2]);
                pass.setUniform("DynamicTransforms", hidden);
                pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
            }
            pass.setPipeline(PIPELINES[throughWalls && !dimOccluded ? 1 : 0]);
            pass.setUniform("DynamicTransforms", normal);
            pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
        }
        gpu.rotate();
    }

    private static int alpha(int color, double factor) {
        return ((int) Math.min(255, (color >>> 24) * Math.max(0, factor)) << 24)
                | (color & 0xffffff);
    }

    @Override
    public void close() {
        clear();
        vertices = 0;
        lastFrame = 0;
        if (gpu != null) gpu.close();
        gpu = null;
        if (bytes != null) MemoryUtil.memFree(bytes);
        bytes = null;
    }
}
