package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.esp.BlockEspCache;
import com.scrimmy.client.module.visual.BlockESPModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Reuses the particle renderer's extraction/ring-buffer approach, with screen-width AA ribbons. */
public final class BlockEspRenderer implements AutoCloseable {
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline[] PIPELINES = pipelines();
    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};
    private static final int[][] FACES = {{0,2,3,1},{4,5,7,6},{0,1,5,4},{2,6,7,3},{0,4,6,2},{1,3,7,5}};
    private final BlockESPModule module;
    private final Matrix4f view = new Matrix4f(), culling = new Matrix4f(), identity = new Matrix4f(), inverseView = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector4f viewport = new Vector4f();
    private final Vector3f zero = new Vector3f(), forward = new Vector3f();
    private final long[] order = new long[BlockEspCache.MAX_RENDERED * 2];
    private final float[] corners = new float[24];
    private ByteBuffer bytes;
    private MappableRingBuffer gpu;
    private int vertices;
    private boolean throughWalls, dimOccluded;
    private float hiddenOpacity;
    private long lastFrame;

    public BlockEspRenderer(BlockESPModule module) { this.module = module; }
    private static RenderPipeline[] pipelines() {
        RenderPipeline[] result = new RenderPipeline[3];
        DepthTestFunction[] depth = {DepthTestFunction.LEQUAL_DEPTH_TEST, DepthTestFunction.NO_DEPTH_TEST, DepthTestFunction.GREATER_DEPTH_TEST};
        for (int i = 0; i < result.length; i++) result[i] = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", "pipeline/block_esp_" + i))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withCull(false).withDepthWrite(false)
            .withDepthTestFunction(depth[i]).withBlend(BlendFunction.TRANSLUCENT).build());
        return result;
    }
    public void extract(WorldExtractionContext context) {
        vertices = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || module.cache.world() != mc.level || mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 1.0 / 60 : Math.min(.25, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
        double partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        double px = mc.player.xo + (mc.player.getX() - mc.player.xo) * partial;
        double py = mc.player.yo + (mc.player.getY() - mc.player.yo) * partial;
        double pz = mc.player.zo + (mc.player.getZ() - mc.player.zo) * partial;
        view.set(context.viewMatrix()); culling.set(context.cullProjectionMatrix()).mul(view); frustum.set(culling);
        inverseView.set(view).invert().transformPosition(forward.set(0, 0, -.15f));
        throughWalls = module.throughWalls.get(); dimOccluded = module.dimOccluded.get(); hiddenOpacity = module.hiddenOpacity.get().floatValue();
        long target = 0;
        boolean hide = module.hideTarget.get() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
        if (hide) target = ((BlockHitResult)mc.hitResult).getBlockPos().asLong();
        double range = module.range.get(), blend = 1 - Math.exp(-dt * 4 / module.fadeTime.get());
        int count = 0;
        for (int i = 0; i < module.cache.size();) {
            BlockEspCache.Visual visual = module.cache.visual(i);
            long pos = visual.entry.pos;
            double x = BlockPos.getX(pos) + .5 - cx, y = BlockPos.getY(pos) + .5 - cy, z = BlockPos.getZ(pos) + .5 - cz;
            double dx = BlockPos.getX(pos) + .5 - px, dy = BlockPos.getY(pos) + .5 - py, dz = BlockPos.getZ(pos) + .5 - pz;
            double distance = dx*dx + dy*dy + dz*dz;
            boolean wanted = visual.wanted && visual.entry.alive() && distance <= range * range && !(hide && pos == target);
            visual.alpha += ((wanted ? 1 : 0) - visual.alpha) * blend;
            if (!visual.wanted || !visual.entry.alive()) {
                if (visual.alpha < .002) { module.cache.removeVisual(i); continue; }
            }
            if (visual.alpha >= .002 && frustum.testAab((float)x-.51f, (float)y-.51f, (float)z-.51f, (float)x+.51f, (float)y+.51f, (float)z+.51f))
                order[count++] = ((long)Float.floatToRawIntBits((float)distance) << 32) | i;
            i++;
        }
        if (count == 0) return;
        Arrays.sort(order, 0, count);
        count = Math.min(count, module.maxBlocks.get().intValue());
        ensure(count * 76 * FORMAT.getVertexSize());
        boolean fill = !module.mode.get().equals("Outline"), outline = !module.mode.get().equals("Fill");
        double seconds = now / 1_000_000_000.0;
        double pulse = module.pulse.get() ? 1 - module.pulseStrength.get() * (.5 + .5 * Math.sin(seconds * module.pulseSpeed.get() * Math.PI * 2)) : 1;
        // The capped nearest set is drawn far to near for stable translucent blending.
        for (int j = count - 1; j >= 0; j--) {
            BlockEspCache.Visual visual = module.cache.visual((int)order[j]);
            long pos = visual.entry.pos;
            double x = BlockPos.getX(pos) - cx, y = BlockPos.getY(pos) - cy, z = BlockPos.getZ(pos) - cz;
            double distance = Math.sqrt(Float.intBitsToFloat((int)(order[j] >>> 32)));
            double opacity = visual.alpha * pulse * (1 - module.distanceFade.get() * Math.min(1, distance / range));
            int color = alpha(module.color(visual.entry.block, seconds, distance), opacity);
            for (int v = 0; v < 8; v++) {
                corners[v*3] = (float)(x + ((v & 1) == 0 ? -.002 : 1.002));
                corners[v*3+1] = (float)(y + ((v & 2) == 0 ? -.002 : 1.002));
                corners[v*3+2] = (float)(z + ((v & 4) == 0 ? -.002 : 1.002));
            }
            if (fill && module.fillOpacity.get() > 0) {
                int fillColor = alpha(color, module.fillOpacity.get());
                for (int[] face : FACES) for (int v : face) vertex(corners[v*3], corners[v*3+1], corners[v*3+2], fillColor, 0, 0, 0, 0, 0);
            }
            if (outline) for (int[] edge : EDGES) {
                int a = edge[0]*3, b = edge[1]*3;
                line(corners[a], corners[a+1], corners[a+2], corners[b], corners[b+1], corners[b+2], color, module.thickness.get().floatValue());
            }
            if (module.tracers.get() && j < module.maxTracers.get().intValue())
                line(forward.x, forward.y, forward.z, (float)x+.5f, (float)y+.5f, (float)z+.5f, alpha(color, module.tracerOpacity.get()), module.tracerWidth.get().floatValue());
        }
        bytes.flip();
    }
    private void ensure(int required) {
        if (bytes != null && bytes.capacity() >= required) { bytes.clear(); return; }
        if (bytes != null) MemoryUtil.memFree(bytes);
        bytes = MemoryUtil.memAlloc(Math.max(65536, Integer.highestOneBit(required - 1) << 1)).order(ByteOrder.nativeOrder());
    }
    private void line(float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        // Clip the ribbon's center line before projection, including crosshair tracers.
        float za = view.m02()*ax + view.m12()*ay + view.m22()*az + view.m32();
        float zb = view.m02()*bx + view.m12()*by + view.m22()*bz + view.m32();
        if (za > -.06f && zb > -.06f) return;
        if (za > -.06f) {
            float t = (-.06f - za) / (zb - za); ax += (bx-ax)*t; ay += (by-ay)*t; az += (bz-az)*t;
        } else if (zb > -.06f) {
            float t = (-.06f - zb) / (za - zb); bx += (ax-bx)*t; by += (ay-by)*t; bz += (az-bz)*t;
        }
        vertex(ax,ay,az,color,bx,by,bz,-1000,width);
        vertex(ax,ay,az,color,bx,by,bz,1000,width);
        vertex(bx,by,bz,color,ax,ay,az,-1000,-width);
        vertex(bx,by,bz,color,ax,ay,az,1000,-width);
    }
    private void vertex(float x, float y, float z, int color, float otherX, float otherY, float otherZ, int side, float width) {
        bytes.putFloat(x).putFloat(y).putFloat(z);
        bytes.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)(color>>>24));
        bytes.putFloat(otherX).putFloat(otherY);
        int bits = Float.floatToRawIntBits(otherZ);
        bytes.putShort((short)bits).putShort((short)(bits>>>16));
        bytes.putShort((short)side).putShort((short)(width*100)); vertices++;
    }
    public void draw(WorldRenderContext context) {
        if (vertices == 0 || !module.enabled()) return;
        if (gpu == null || gpu.size() < bytes.remaining()) {
            if (gpu != null) gpu.close();
            gpu = new MappableRingBuffer(() -> "Scrimmy BlockESP vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes.capacity());
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mapped = encoder.mapBuffer(gpu.currentBuffer().slice(0, bytes.remaining()), false, true)) { mapped.data().put(bytes); bytes.rewind(); }
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(vertices / 4 * 6);
        var target = Minecraft.getInstance().getMainRenderTarget();
        viewport.set(target.width, target.height, 1, 1);
        var normal = RenderSystem.getDynamicUniforms().writeTransform(view, viewport, zero, identity);
        viewport.z = hiddenOpacity;
        var hidden = RenderSystem.getDynamicUniforms().writeTransform(view, viewport, zero, identity);
        try (var pass = encoder.createRenderPass(() -> "Scrimmy BlockESP", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINES[throughWalls && !dimOccluded ? 1 : 0]);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setVertexBuffer(0, gpu.currentBuffer()); pass.setIndexBuffer(indices, sequential.type());
            if (throughWalls && dimOccluded) {
                pass.setPipeline(PIPELINES[2]); pass.setUniform("DynamicTransforms", hidden); pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
            }
            pass.setPipeline(PIPELINES[throughWalls && !dimOccluded ? 1 : 0]);
            pass.setUniform("DynamicTransforms", normal); pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
        }
        gpu.rotate();
    }
    private static int alpha(int color, double factor) { return ((int)Math.min(255, (color>>>24)*Math.max(0, factor))<<24) | (color & 0xffffff); }
    @Override public void close() {
        vertices = 0; lastFrame = 0;
        if (gpu != null) gpu.close(); gpu = null;
        if (bytes != null) MemoryUtil.memFree(bytes); bytes = null;
    }
}
