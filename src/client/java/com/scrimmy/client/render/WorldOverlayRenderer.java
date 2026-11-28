package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.FrustumIntersection;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
import java.util.function.Supplier;

/** Shared through-wall geometry and anti-aliased screen-width lines, extracted from Chunk Finder. */
public final class WorldOverlayRenderer implements AutoCloseable {
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline PIPELINE = pipeline(true), DEPTH_PIPELINE = pipeline(false);
    private static RenderPipeline pipeline(boolean throughWalls) { return RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", throughWalls ? "pipeline/world_overlay" : "pipeline/world_overlay_depth"))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withCull(false).withDepthWrite(false)
            .withDepthTestFunction(throughWalls ? DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT).build()); }
    private final Matrix4f view = new Matrix4f(), identity = new Matrix4f();
    private final Vector4f viewport = new Vector4f();
    private final Vector3f zero = new Vector3f();
    private final Supplier<String> label;
    private ByteBuffer bytes;
    private MappableRingBuffer gpu;
    private int vertices;
    public WorldOverlayRenderer(String name) { label = () -> name; }
    /** Owners reserve their bounded worst-case capacity during initialization, never on frames. */
    public void reserve(int vertexCount) {
        int required = vertexCount * FORMAT.getVertexSize();
        if (bytes != null && bytes.capacity() >= required) return;
        if (bytes != null) MemoryUtil.memFree(bytes);
        bytes = MemoryUtil.memAlloc(Math.max(65536, Integer.highestOneBit(required - 1) << 1)).order(ByteOrder.nativeOrder());
    }
    public void clear() { vertices = 0; if (bytes != null) bytes.clear(); }
    public void begin(Matrix4f view) { clear(); this.view.set(view); }
    public void line(float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        float za = view.m02() * ax + view.m12() * ay + view.m22() * az + view.m32();
        float zb = view.m02() * bx + view.m12() * by + view.m22() * bz + view.m32();
        if (za > -.06f && zb > -.06f) return;
        if (za > -.06f) {
            float t = (-.06f - za) / (zb - za); ax += (bx - ax) * t; ay += (by - ay) * t; az += (bz - az) * t;
        } else if (zb > -.06f) {
            float t = (-.06f - zb) / (za - zb); bx += (ax - bx) * t; by += (ay - by) * t; bz += (az - bz) * t;
        }
        vertex(ax, ay, az, color, bx, by, bz, -1000, width);
        vertex(ax, ay, az, color, bx, by, bz, 1000, width);
        vertex(bx, by, bz, color, ax, ay, az, -1000, -width);
        vertex(bx, by, bz, color, ax, ay, az, 1000, -width);
    }
    public void vertex(float x, float y, float z, int color, float otherX, float otherY, float otherZ, int side, float width) {
        bytes.putFloat(x).putFloat(y).putFloat(z);
        bytes.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color).put((byte)(color >>> 24));
        bytes.putFloat(otherX).putFloat(otherY);
        int bits = Float.floatToRawIntBits(otherZ);
        bytes.putShort((short)bits).putShort((short)(bits >>> 16));
        bytes.putShort((short)side).putShort((short)(width * 100)); vertices++;
    }
    public void draw(WorldRenderContext context) {
        draw(context, true);
    }
    public void draw(WorldRenderContext context, boolean throughWalls) {
        if (vertices == 0) return;
        bytes.limit(vertices * FORMAT.getVertexSize()).position(0);
        if (gpu == null || gpu.size() < bytes.remaining()) {
            if (gpu != null) gpu.close();
            gpu = new MappableRingBuffer(label, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes.capacity());
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mapped = encoder.mapBuffer(gpu.currentBuffer().slice(0, bytes.remaining()), false, true)) {
            mapped.data().put(bytes); bytes.rewind();
        }
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(vertices / 4 * 6);
        var target = Minecraft.getInstance().getMainRenderTarget();
        viewport.set(target.width, target.height, 1, 1);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(view, viewport, zero, identity);
        try (var pass = encoder.createRenderPass(label, target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(throughWalls ? PIPELINE : DEPTH_PIPELINE); RenderSystem.bindDefaultUniforms(pass);
            pass.setVertexBuffer(0, gpu.currentBuffer()); pass.setIndexBuffer(indices, sequential.type());
            pass.setUniform("DynamicTransforms", transform); pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
        }
        gpu.rotate();
    }
    @Override public void close() {
        if (gpu != null) gpu.close(); gpu = null;
        if (bytes != null) MemoryUtil.memFree(bytes); bytes = null; vertices = 0;
    }
}
