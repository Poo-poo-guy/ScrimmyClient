package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.esp.BlockEspCache;
import com.scrimmy.client.module.visual.BlockESPModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** Retained block-local geometry. Camera motion changes uniforms/draw ranges, never vertices. */
public final class BlockEspRenderer implements AutoCloseable {
    private static VertexFormatElement animationElement() {
        // This is a client GPU attribute, not a Minecraft/synced registry entry.
        for (int id = 0; id < VertexFormatElement.MAX_COUNT; id++)
            if (VertexFormatElement.byId(id) == null)
                return VertexFormatElement.register(id, 0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 3);
        throw new IllegalStateException("No vertex attribute available for BlockESP");
    }
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2)
        .add("Animation", animationElement()).add("Normal", VertexFormatElement.NORMAL).padding(1).build();
    private static final RenderPipeline[] PIPELINES = pipelines();
    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};
    private static final int[][] FACES = {{0,2,3,1},{4,5,7,6},{0,1,5,4},{2,6,7,3},{0,4,6,2},{1,3,7,5}};
    private static final Supplier<String> LABEL = () -> "Scrimmy BlockESP";
    private final BlockESPModule module;
    private final BlockEspCamera frame = new BlockEspCamera();
    private final Matrix4f parameters = new Matrix4f();
    private final Vector4f viewport = new Vector4f();
    private final Vector3f zero = new Vector3f();
    private final long[] order = new long[BlockEspCache.MAX_RENDERED * 2];
    private final float[] corners = new float[24];
    private ByteBuffer bytes, projectionBytes;
    private GpuBuffer gpu, projectionGpu;
    private GpuBufferSlice projectionSlice;
    private int vertices, count, blockVertices, boxIndices, originX, originY, originZ;
    private long revision = Long.MIN_VALUE;
    private boolean dirty = true, upload, throughWalls;
    private float animationAlpha, animationTime, animationTarget;

    public BlockEspRenderer(BlockESPModule module) { this.module = module; }
    public void invalidate() { dirty = true; }
    private static RenderPipeline[] pipelines() {
        RenderPipeline[] result = new RenderPipeline[3];
        DepthTestFunction[] depth = {DepthTestFunction.LEQUAL_DEPTH_TEST, DepthTestFunction.NO_DEPTH_TEST, DepthTestFunction.GREATER_DEPTH_TEST};
        for (int i = 0; i < result.length; i++) result[i] = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", "pipeline/block_esp_retained_" + i))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp_retained"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("BlockEspProjection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withCull(false).withDepthWrite(false)
            .withDepthTestFunction(depth[i]).withBlend(BlendFunction.TRANSLUCENT).build());
        return result;
    }
    public void extract(WorldExtractionContext context) {
        count = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || module.cache.world() != mc.level || mc.level == null || mc.player == null) return;
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
        float partial = context.camera().getPartialTickTime();
        double px = BlockEspCamera.interpolate(mc.player.xo, mc.player.getX(), partial);
        double py = BlockEspCamera.interpolate(mc.player.yo, mc.player.getY(), partial);
        double pz = BlockEspCamera.interpolate(mc.player.zo, mc.player.getZ(), partial);
        if (dirty || revision != module.cache.renderRevision()) rebuild(px, py, pz);
        frame.update(camera.orientation, cx, cy, cz, px, py, pz, originX, originY, originZ, module.tracerOrigin.get().equals("Feet"));
        throughWalls = module.throughWalls.get();
        double range = module.range.get();
        float now = module.cache.seconds();
        // TextureMat is unused for texturing here and carries shader-only animation/distance data.
        parameters.zero().setColumn(0, viewport.set((float)(px-originX), (float)(py-originY), (float)(pz-originZ), (float)range))
            .setColumn(1, viewport.set((float)(cx-originX), (float)(cy-originY), (float)(cz-originZ), now))
            .setColumn(2, viewport.set(frame.tracerStart.x, frame.tracerStart.y, frame.tracerStart.z, (float)BlockESPModule.DISTANCE_FADE))
            .setColumn(3, viewport.set((float)(4 / BlockESPModule.FADE_TIME), BlockESPModule.PULSE ? (float)BlockESPModule.PULSE_STRENGTH : 0,
                (float)BlockESPModule.PULSE_SPEED, module.tracerOrigin.get().equals("Feet") ? 1 : 0));
        count = BlockEspDrawList.select(module.cache, px, py, pz, range, module.maxBlocks.get().intValue(), order);
    }

    private void rebuild(double px, double py, double pz) {
        // A stable local origin retains float precision even near the world border.
        originX = (int)Math.floor(px); originY = (int)Math.floor(py); originZ = (int)Math.floor(pz);
        boolean fill = !module.mode.get().equals("Outline"), outline = !module.mode.get().equals("Fill");
        blockVertices = (fill ? 24 : 0) + (outline ? 48 : 0) + (module.tracers.get() ? 4 : 0);
        boxIndices = ((fill ? 24 : 0) + (outline ? 48 : 0)) / 4 * 6;
        int required = module.cache.size() * blockVertices * FORMAT.getVertexSize();
        if (bytes == null || bytes.capacity() < required) {
            if (bytes != null) MemoryUtil.memFree(bytes);
            bytes = MemoryUtil.memAlloc(Math.max(65536, Integer.highestOneBit(Math.max(1, required) - 1) << 1)).order(ByteOrder.nativeOrder());
        }
        bytes.clear(); vertices = 0;
        for (int i = module.cache.size() - 1; i >= 0; i--) {
            BlockEspCache.Visual visual = module.cache.visual(i);
            long pos = visual.entry.pos;
            float x = BlockPos.getX(pos) - originX, y = BlockPos.getY(pos) - originY, z = BlockPos.getZ(pos) - originZ;
            animationAlpha = (float)visual.alpha; animationTime = visual.changedAt; animationTarget = visual.wanted ? 1 : 0;
            int color = module.color(visual.entry.block, 0, 0);
            for (int v = 0; v < 8; v++) {
                corners[v*3] = x + ((v & 1) == 0 ? -.002f : 1.002f);
                corners[v*3+1] = y + ((v & 2) == 0 ? -.002f : 1.002f);
                corners[v*3+2] = z + ((v & 4) == 0 ? -.002f : 1.002f);
            }
            if (fill) {
                int fillColor = alpha(color, BlockESPModule.FILL_OPACITY);
                for (int[] face : FACES) for (int v : face) vertex(corners[v*3], corners[v*3+1], corners[v*3+2], fillColor, 0, 0, 0, 0, 0);
            }
            if (outline) for (int[] edge : EDGES) {
                int a = edge[0]*3, b = edge[1]*3;
                line(corners[a], corners[a+1], corners[a+2], corners[b], corners[b+1], corners[b+2], color, (float)BlockESPModule.THICKNESS, 1000);
            }
            // Only endpoints are cached; the shader uses the current frame's steady origin.
            if (module.tracers.get()) line(x+.5f, y+.5f, z+.5f, x+.5f, y+.5f, z+.5f,
                alpha(color, BlockESPModule.TRACER_OPACITY), (float)BlockESPModule.TRACER_WIDTH, 2000);
        }
        bytes.flip(); upload = true; dirty = false; revision = module.cache.renderRevision();
    }
    private void line(float ax, float ay, float az, float bx, float by, float bz, int color, float width, int side) {
        vertex(ax,ay,az,color,bx,by,bz,-side,width);
        vertex(ax,ay,az,color,bx,by,bz,side,width);
        vertex(bx,by,bz,color,ax,ay,az,-side,-width);
        vertex(bx,by,bz,color,ax,ay,az,side,-width);
    }
    private void vertex(float x, float y, float z, int color, float otherX, float otherY, float otherZ, int side, float width) {
        bytes.putFloat(x).putFloat(y).putFloat(z);
        bytes.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)(color>>>24));
        bytes.putFloat(otherX).putFloat(otherY);
        int bits = Float.floatToRawIntBits(otherZ);
        bytes.putShort((short)bits).putShort((short)(bits>>>16));
        bytes.putShort((short)side).putShort((short)(width*100));
        bytes.putFloat(animationAlpha).putFloat(animationTime).putFloat(animationTarget);
        // Corner signs recover the block center in the shader, keeping distance opacity uniform.
        bytes.put((byte)(x < Math.round(x) ? -127 : 127)).put((byte)(y < Math.round(y) ? -127 : 127))
            .put((byte)(z < Math.round(z) ? -127 : 127)).put((byte)0); vertices++;
    }
    public void draw(WorldRenderContext context) {
        if (count == 0 || !module.enabled()) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (upload) {
            if (gpu == null || gpu.size() < bytes.remaining()) {
                if (gpu != null) gpu.close();
                gpu = RenderSystem.getDevice().createBuffer(LABEL, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes.capacity());
            }
            encoder.writeToBuffer(gpu.slice(0, bytes.remaining()), bytes); bytes.rewind(); upload = false;
        }
        if (projectionBytes == null) {
            projectionBytes = MemoryUtil.memAlloc(64).order(ByteOrder.nativeOrder());
            projectionGpu = RenderSystem.getDevice().createBuffer(LABEL, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
            projectionSlice = projectionGpu.slice();
        }
        projectionBytes.clear(); frame.projection.get(0, projectionBytes);
        encoder.writeToBuffer(projectionSlice, projectionBytes); projectionBytes.rewind();
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(vertices / 4 * 6);
        var target = Minecraft.getInstance().getMainRenderTarget();
        viewport.set(target.width, target.height, 1, 1);
        var normal = RenderSystem.getDynamicUniforms().writeTransform(frame.modelView, viewport, zero, parameters);
        viewport.z = (float)BlockESPModule.HIDDEN_OPACITY;
        var hidden = RenderSystem.getDynamicUniforms().writeTransform(frame.modelView, viewport, zero, parameters);
        try (var pass = encoder.createRenderPass(LABEL, target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINES[throughWalls && BlockESPModule.DIM_OCCLUDED ? 0 : 1]);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("BlockEspProjection", projectionSlice);
            pass.setVertexBuffer(0, gpu); pass.setIndexBuffer(indices, sequential.type());
            // The two depth passes partition ALL pixels and only change opacity.
            // The legacy toggle can disable dimming, never hide cached highlights.
            if (throughWalls && BlockESPModule.DIM_OCCLUDED) {
                pass.setPipeline(PIPELINES[2]); pass.setUniform("DynamicTransforms", hidden); drawRanges(pass);
            }
            pass.setPipeline(PIPELINES[throughWalls && BlockESPModule.DIM_OCCLUDED ? 0 : 1]);
            pass.setUniform("DynamicTransforms", normal); drawRanges(pass);
        }
    }
    private void drawRanges(RenderPass pass) {
        int stride = blockVertices / 4 * 6;
        int first = -1, length = 0;
        // Far to near, coalescing adjacent cached blocks into a single draw where possible.
        for (int j = count - 1; j >= 0; j--) {
            int index = (int)order[j] * stride;
            int size = boxIndices + (module.tracers.get() && j < BlockESPModule.MAX_TRACERS ? 6 : 0);
            if (first >= 0 && first + length == index) length += size;
            else {
                if (first >= 0) pass.drawIndexed(0, first, length, 1);
                first = index; length = size;
            }
        }
        if (first >= 0) pass.drawIndexed(0, first, length, 1);
    }
    private static int alpha(int color, double factor) { return ((int)Math.min(255, (color>>>24)*Math.max(0, factor))<<24) | (color & 0xffffff); }
    @Override public void close() {
        count = vertices = 0; dirty = true; revision = Long.MIN_VALUE;
        if (gpu != null) gpu.close(); gpu = null;
        if (projectionGpu != null) projectionGpu.close(); projectionGpu = null; projectionSlice = null;
        if (projectionBytes != null) MemoryUtil.memFree(projectionBytes); projectionBytes = null;
        if (bytes != null) MemoryUtil.memFree(bytes); bytes = null;
    }
}
