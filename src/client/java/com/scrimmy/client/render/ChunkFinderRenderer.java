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
import com.scrimmy.client.basefinding.DetectionLog.Detection;
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
import java.util.Comparator;
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
            identity = new Matrix4f(),
            inverseView = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector4f viewport = new Vector4f();
    private final Vector3f zero = new Vector3f(), forward = new Vector3f();
    private final long[] order = new long[512];
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

    private static final int MAX_VISIBLE = 256;
    private final Map<Long, Visual> cached = new HashMap<>();
    private Visual[] visuals = new Visual[0];
    private final Visual[] projected = new Visual[MAX_VISIBLE];
    private final float[] labelBounds = new float[MAX_VISIBLE * 4];
    private float labelResponse = 1;
    private final Vector4f clip = new Vector4f();
    private int labelCount;
    private String toastText;
    private long toastTime;

    public ChunkFinderRenderer(Basefinding module) {
        this.module = module;
    }

    public void initialize() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("scrimmyclient", "chunk_finder"),
                (graphics, delta) -> hud(graphics));
    }

    public void toast(String text) {
        toastText = text;
        toastTime = System.nanoTime();
    }

    public void clear() {
        cached.clear();
        visuals = new Visual[0];
        labelCount = vertices = 0;
        toastText = null;
    }

    /** Called on ticks/changes, not frames. Bound geometry and label work to the nearest 256. */
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        cached.values().removeIf(v -> !v.wanted && v.alpha < .002);
        for (Visual v : cached.values()) v.wanted = false;
        double px = mc.player.getX(),
                pz = mc.player.getZ(),
                limit = module.distance.get() * module.distance.get();
        List<Detection> nearest = new ArrayList<>();
        for (Detection d : module.detections()) {
            double dx = d.chunkX() * 16 + 8 - px, dz = d.chunkZ() * 16 + 8 - pz;
            if (module.display(d) && dx * dx + dz * dz <= limit) nearest.add(d);
        }
        nearest.sort(
                Comparator.comparingDouble(
                        d -> {
                            double dx = d.chunkX() * 16 + 8 - px, dz = d.chunkZ() * 16 + 8 - pz;
                            return dx * dx + dz * dz;
                        }));
        for (int i = 0; i < Math.min(MAX_VISIBLE, nearest.size()); i++) {
            Detection d = nearest.get(i);
            Visual v = cached.get(d.key());
            if (v == null) {
                if (cached.size() >= 512) continue;
                v = new Visual();
                cached.put(d.key(), v);
            }
            v.detection = d;
            v.wanted = true;
            v.text =
                    "["
                            + d.chunkX()
                            + ", "
                            + d.chunkZ()
                            + "] "
                            + Math.round(d.score())
                            + "/100 · "
                            + d.clusters()
                            + " clusters";
            v.textWidth = mc.font.width(v.text);
        }
        visuals = cached.values().toArray(Visual[]::new);
    }

    public void extract(WorldExtractionContext context) {
        vertices = labelCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || mc.player == null
                || module.world() != mc.level
                || !mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 1.0 / 60 : Math.min(.25, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        labelResponse = (float) (1 - Math.exp(-dt * 14));
        view.set(context.viewMatrix());
        culling.set(context.cullProjectionMatrix()).mul(view);
        frustum.set(culling);
        inverseView.set(view).invert().transformPosition(forward.set(0, 0, -.15f));
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x,
                cy = camera.pos.y,
                cz = camera.pos.z,
                range = module.distance.get();
        float bottom =
                (float)
                        Math.max(
                                mc.level.getMinY(), Math.min(module.minY.get(), module.maxY.get()));
        float top =
                (float)
                        Math.min(
                                mc.level.getMaxY() + 1,
                                Math.max(module.minY.get(), module.maxY.get()) + 1);
        if (top <= bottom) return;
        double blend = 1 - Math.exp(-dt * 4 / module.fade.get());
        int count = 0;
        for (int i = 0; i < visuals.length; i++) {
            Visual v = visuals[i];
            Detection d = v.detection;
            double dx = d.chunkX() * 16 + 8 - cx,
                    dz = d.chunkZ() * 16 + 8 - cz,
                    dist = dx * dx + dz * dz;
            boolean wanted = v.wanted && module.display(d) && dist < range * range;
            v.alpha += ((wanted ? 1 : 0) - v.alpha) * blend;
            if (v.alpha < .002
                    || !frustum.testAab(
                            (float) (dx - 8),
                            (float) (bottom - cy),
                            (float) (dz - 8),
                            (float) (dx + 8),
                            (float) (top - cy),
                            (float) (dz + 8))) continue;
            order[count++] = ((long) Float.floatToRawIntBits((float) dist) << 32) | i;
        }
        if (count == 0) return;
        Arrays.sort(order, 0, count);
        // Box:72 vertices; tracer:4; beams capped at 16 geodes/chunk:64. Reuse one native buffer.
        ensure(count * 144 * FORMAT.getVertexSize());
        throughWalls = true;
        dimOccluded = false;
        hiddenOpacity = 1;
        double seconds = now / 1_000_000_000.0;
        for (int j = count - 1; j >= 0; j--) {
            Visual v = visuals[(int) order[j]];
            Detection d = v.detection;
            float x = (float) (d.chunkX() * 16 - cx), z = (float) (d.chunkZ() * 16 - cz);
            double distance = Math.sqrt(Float.intBitsToFloat((int) (order[j] >>> 32)));
            double opacity = v.alpha * Math.max(0, 1 - distance / range);
            int base =
                    module.singleMode.get()
                            ? module.singleColor.get()
                            : d.score() >= 80
                                    ? module.highColor.get()
                                    : d.score() >= 60
                                            ? module.mediumColor.get()
                                            : module.lowColor.get();
            if (module.pulse.get())
                base =
                        GradientSetting.mix(
                                module.pulseA.get(),
                                module.pulseB.get(),
                                .5 + .5 * Math.sin(seconds * Math.PI));
            int color = alpha(base, opacity);
            for (int k = 0; k < 8; k++) {
                corners[k * 3] = x + ((k & 1) == 0 ? 0 : 16);
                corners[k * 3 + 1] = (float) (((k & 2) == 0 ? bottom : top) - cy);
                corners[k * 3 + 2] = z + ((k & 4) == 0 ? 0 : 16);
            }
            if (module.fill.get())
                for (int[] face : FACES)
                    for (int k : face)
                        vertex(
                                corners[k * 3],
                                corners[k * 3 + 1],
                                corners[k * 3 + 2],
                                alpha(color, module.fillAlpha.get()),
                                0,
                                0,
                                0,
                                0,
                                0);
            if (module.outline.get())
                for (int[] edge : EDGES) {
                    int a = edge[0] * 3, b = edge[1] * 3;
                    line(
                            corners[a],
                            corners[a + 1],
                            corners[a + 2],
                            corners[b],
                            corners[b + 1],
                            corners[b + 2],
                            alpha(module.outlineColor.get(), opacity),
                            module.outlineWidth.get().floatValue());
                }
            float gy =
                    d.geodes().isEmpty() ? (bottom + top) * .5f : d.geodes().getFirst().y() + .5f;
            if (module.tracers.get())
                line(forward.x, forward.y, forward.z, x + 8, (float) (gy - cy), z + 8, color, 1);
            if (module.beams.get())
                for (int k = 0; k < Math.min(16, d.geodes().size()); k++) {
                    var g = d.geodes().get(k);
                    float gx = (float) (g.x() + .5 - cx), gz = (float) (g.z() + .5 - cz);
                    line(gx, (float) (g.y() - cy), gz, gx, (float) (top - cy), gz, color, 3);
                }
            if (module.labels.get() && labelCount < MAX_VISIBLE) {
                clip.set(x + 8, (float) (gy + 2 - cy), z + 8, 1);
                culling.transform(clip);
                if (clip.w > .01 && Math.abs(clip.x) < clip.w && Math.abs(clip.y) < clip.w) {
                    v.screenX = clip.x / clip.w;
                    v.screenY = clip.y / clip.w;
                    v.labelAlpha = opacity;
                    v.labelScale = (float) Math.max(.55, Math.min(1.2, 40 / Math.max(1, distance)));
                    projected[labelCount++] = v;
                }
            }
        }
        bytes.flip();
    }

    private void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || mc.level != module.world()) return;
        int placed = 0;
        if (module.active() && module.labels.get())
            // Prefer nearer labels and move overlaps down with a damped offset. Bounds and
            // strings are reused; crowded labels are skipped while settling instead of overdrawn.
            for (int i = labelCount - 1; i >= 0; i--) {
                Visual v = projected[i];
                if (v.labelAlpha < .01) continue;
                float x = (v.screenX + 1) * mc.getWindow().getGuiScaledWidth() / 2f;
                float y = (1 - v.screenY) * mc.getWindow().getGuiScaledHeight() / 2f;
                float half = (v.textWidth / 2f + 5) * v.labelScale;
                float target = y;
                for (int j = 0; j < placed; j++) {
                    int b = j * 4;
                    if (x + half >= labelBounds[b]
                            && x - half <= labelBounds[b + 2]
                            && target + 13 * v.labelScale >= labelBounds[b + 1]
                            && target - 4 * v.labelScale <= labelBounds[b + 3])
                        target = labelBounds[b + 3] + 4 * v.labelScale + 3;
                }
                v.labelOffset += (target - y - v.labelOffset) * labelResponse;
                y += v.labelOffset;
                boolean overlap = false;
                for (int j = 0; j < placed; j++) {
                    int b = j * 4;
                    if (x + half >= labelBounds[b]
                            && x - half <= labelBounds[b + 2]
                            && y + 13 * v.labelScale >= labelBounds[b + 1]
                            && y - 4 * v.labelScale <= labelBounds[b + 3]) {
                        overlap = true;
                        break;
                    }
                }
                if (overlap || y + 13 * v.labelScale > mc.getWindow().getGuiScaledHeight())
                    continue;
                int b = placed++ * 4;
                labelBounds[b] = x - half;
                labelBounds[b + 1] = y - 4 * v.labelScale;
                labelBounds[b + 2] = x + half;
                labelBounds[b + 3] = y + 13 * v.labelScale;
                g.pose().pushMatrix();
                g.pose().translate(x, y);
                g.pose().scale(v.labelScale, v.labelScale);
                RoundedRenderer.panel(
                        g,
                        -v.textWidth / 2 - 5,
                        -4,
                        v.textWidth + 10,
                        17,
                        4,
                        alpha(0xc0192936, v.labelAlpha),
                        alpha(0x558da9bc, v.labelAlpha),
                        0,
                        0);
                g.drawString(
                        mc.font,
                        v.text,
                        -v.textWidth / 2,
                        0,
                        alpha(module.labelColor.get(), v.labelAlpha),
                        false);
                g.pose().popMatrix();
            }
        if (toastText != null && module.active() && module.toast.get()) {
            double age = (System.nanoTime() - toastTime) / 1_000_000_000.0;
            if (age > 5) {
                toastText = null;
                return;
            }
            double visibility = Math.min(1, age / .3) * Math.min(1, (5 - age) / .5);
            int w =
                    Math.min(
                            mc.getWindow().getGuiScaledWidth() - 16, mc.font.width(toastText) + 20);
            float x =
                    mc.getWindow().getGuiScaledWidth()
                            - w
                            - 8
                            + (float) ((1 - visibility) * (w + 8));
            RoundedRenderer.panel(
                    g,
                    (int) x,
                    12,
                    w,
                    37,
                    8,
                    alpha(0xe01b2b39, visibility),
                    alpha(ScrimmyClient.THEME.accent.get(), visibility),
                    .2f,
                    .3f);
            g.enableScissor((int) x + 7, 12, (int) x + w - 7, 49);
            g.drawString(
                    mc.font,
                    "Basefinding",
                    (int) x + 9,
                    18,
                    alpha(ScrimmyClient.THEME.accent.get(), visibility),
                    false);
            g.drawString(mc.font, toastText, (int) x + 9, 32, alpha(0xffedf6ff, visibility), false);
            g.disableScissor();
        }
    }

    private static final class Visual {
        Detection detection;
        String text;
        int textWidth;
        boolean wanted;
        double alpha, labelAlpha;
        float screenX, screenY, labelScale, labelOffset;
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
