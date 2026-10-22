package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.module.visual.HitParticles;
import com.scrimmy.client.particle.ParticleMath;
import com.scrimmy.client.particle.ParticlePool;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
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
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Extracts camera-relative vertices once; draws the whole effect in at most two passes. */
public final class HitParticleRenderer implements AutoCloseable {
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline[] PIPELINES = pipelines();
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private final HitParticles module;
    private final Batch normal = new Batch(), light = new Batch();
    private final Matrix4f view = new Matrix4f(), culling = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector3f right = new Vector3f(), up = new Vector3f();
    private final long[] order = new long[ParticlePool.CAPACITY];
    private boolean throughWalls;
    private double cameraX, cameraY, cameraZ;
    private float rx, ry, rz, ux, uy, uz;

    public HitParticleRenderer(HitParticles module) { this.module = module; }
    private static RenderPipeline[] pipelines() {
        RenderPipeline[] result = new RenderPipeline[4];
        for (int i = 0; i < result.length; i++) result[i] = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", "pipeline/hit_particles_" + i))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/hit_particle"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/hit_particle"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withCull(false).withDepthWrite(false)
            .withDepthTestFunction((i & 2) == 0 ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
            .withBlend((i & 1) == 0 ? BlendFunction.TRANSLUCENT : new BlendFunction(com.mojang.blaze3d.platform.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.DestFactor.ONE))
            .build());
        return result;
    }
    public void extract(WorldExtractionContext context) {
        clear();
        if (!module.enabled() || module.particles.size() == 0) return;
        view.set(context.viewMatrix());
        culling.set(context.cullProjectionMatrix()).mul(view);
        frustum.set(culling);
        var camera = context.worldState().cameraRenderState;
        cameraX = camera.pos.x; cameraY = camera.pos.y; cameraZ = camera.pos.z;
        if (module.billboard.get()) {
            right.set(1, 0, 0).rotate(camera.orientation); up.set(0, 1, 0).rotate(camera.orientation);
        } else { right.set(1, 0, 0); up.set(0, 1, 0); }
        throughWalls = module.throughWalls.get();
        double delta = context.tickCounter().getGameTimeDeltaPartialTick(false), renderDistance = module.distance.get();
        int visible = 0;
        for (int i = 0; i < module.particles.size(); i++) {
            ParticlePool.Particle p = module.particles.get(i);
            double x = lerp(p.oldX, p.x, delta) - cameraX, y = lerp(p.oldY, p.y, delta) - cameraY, z = lerp(p.oldZ, p.z, delta) - cameraZ;
            double d = x * x + y * y + z * z;
            if (d > renderDistance * renderDistance) continue;
            // A conservative sphere also keeps halos, overshoot, and nearby trail samples visible.
            float radius = (float)(p.size * module.size.get() / Math.max(.001, p.baseSize) * (3 + module.glowSize.get() + module.endSize.get()) + (module.trail.get() ? 4 : 0));
            if (!frustum.testSphere((float)x, (float)y, (float)z, radius)) continue;
            order[visible++] = ((long)Float.floatToRawIntBits((float)d) << 32) | i;
        }
        if (visible == 0) return;
        int samples = module.trail.get() ? module.trailLength.get().intValue() : 0;
        int maxBytes = visible * (samples + 3) * 4 * FORMAT.getVertexSize();
        normal.ensure(maxBytes); light.ensure(maxBytes);
        Arrays.sort(order, 0, visible);
        for (int v = visible - 1; v >= 0; v--) {
            ParticlePool.Particle p = module.particles.get((int)order[v]);
            double age = Math.max(0, p.age - .05 + delta * .05), progress = age / p.lifetime;
            double alpha = ParticleMath.alpha(module.fadeEase.get(), age, p.lifetime, Math.min(module.pop.get(), p.lifetime * .3), module.alphaStart.get(), module.alphaEnd.get());
            double scale = ParticleMath.scale(module.scaleEase.get(), age, p.lifetime, Math.min(module.pop.get(), p.lifetime * .3), module.endSize.get());
            double pulse = 1 - module.twinkleStrength.get() * (.5 + .5 * Math.sin(age * module.twinkleSpeed.get() * Math.PI * 2 + p.phase));
            if (module.twinkle.get()) { if (module.twinkleMode.get().equals("Alpha")) alpha *= pulse; else scale *= pulse; }
            if (alpha < .003 || scale < .001) continue;
            int color = alpha(module.color(p, progress, context.worldState().gameTime / 20.0 + delta * .05), alpha);
            double angle = lerp(p.oldAngle, p.angle, delta), cos = Math.cos(angle), sin = Math.sin(angle);
            rx = (float)(right.x * cos + up.x * sin); ry = (float)(right.y * cos + up.y * sin); rz = (float)(right.z * cos + up.z * sin);
            ux = (float)(up.x * cos - right.x * sin); uy = (float)(up.y * cos - right.y * sin); uz = (float)(up.z * cos - right.z * sin);
            float size;
            // Size randomization is sampled at spawn; the base size still responds live.
            size = (float)(p.size * scale * module.size.get() / Math.max(.001, p.baseSize));
            double x = lerp(p.oldX, p.x, delta) - cameraX, y = lerp(p.oldY, p.y, delta) - cameraY, z = lerp(p.oldZ, p.z, delta) - cameraZ;
            Batch body = module.additive.get() ? light : normal;
            int points = Integer.parseInt(module.points.get());
            if (samples > 0) for (int j = Math.min(samples, p.trailCount) - 1; j >= 0; j--) {
                int index = Math.floorMod(p.trailHead - j, ParticlePool.TRAIL);
                int previous = Math.floorMod(index - 1, ParticlePool.TRAIL);
                double tx = p.trailX[index], ty = p.trailY[index], tz = p.trailZ[index];
                if (j + 1 < p.trailCount) { tx = lerp(p.trailX[previous], tx, delta); ty = lerp(p.trailY[previous], ty, delta); tz = lerp(p.trailZ[previous], tz, delta); }
                double f = 1 - (j + 1.0) / (samples + 1);
                quad(body, tx - cameraX, ty - cameraY, tz - cameraZ, size * (float)f, alpha(color, Math.pow(f, module.trailFade.get()) * .45), p.shape, points, 0, 0);
            }
            if (module.glow.get() > 0) quad(light, x, y, z, size, alpha(color, Math.min(1, module.glow.get())), p.shape, points, 2, module.glowSize.get());
            if (module.outline.get()) quad(body, x, y, z, size, alpha(module.outlineColor.get(), alpha), p.shape, points, 1, module.outlineWidth.get());
            quad(body, x, y, z, size, color, p.shape, points, 0, 0);
        }
        normal.finish(); light.finish();
    }
    private void quad(Batch batch, double x, double y, double z, float size, int color, int shape, int points, int kind, double parameter) {
        float extent = kind == 2 ? 1 + (float)parameter * 2 : 1.5f;
        vertex(batch, x, y, z, -extent, -extent, size, color, shape, points, kind, parameter);
        vertex(batch, x, y, z, extent, -extent, size, color, shape, points, kind, parameter);
        vertex(batch, x, y, z, extent, extent, size, color, shape, points, kind, parameter);
        vertex(batch, x, y, z, -extent, extent, size, color, shape, points, kind, parameter);
    }
    private void vertex(Batch batch, double x, double y, double z, float u, float v, float size, int color, int shape, int points, int kind, double parameter) {
        ByteBuffer b = batch.bytes;
        b.putFloat((float)x + size * (rx * u + ux * v)).putFloat((float)y + size * (ry * u + uy * v)).putFloat((float)z + size * (rz * u + uz * v));
        b.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color).put((byte)(color >>> 24));
        b.putFloat(u).putFloat(v);
        b.putShort((short)shape).putShort((short)(module.inner.get() * 1000));
        b.putShort((short)(points | (kind << 8))).putShort((short)(parameter * 1000));
        batch.vertices++;
    }
    public void draw(WorldRenderContext context) {
        if (normal.vertices == 0 && light.vertices == 0) return;
        var transform = RenderSystem.getDynamicUniforms().writeTransform(view, WHITE, ZERO, IDENTITY);
        var target = Minecraft.getInstance().getMainRenderTarget();
        normal.upload(); light.upload();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Scrimmy hit particles", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINES[throughWalls ? 2 : 0]);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            light.draw(pass, PIPELINES[(throughWalls ? 2 : 0) | 1]);
            normal.draw(pass, PIPELINES[throughWalls ? 2 : 0]);
        }
        normal.rotate(); light.rotate();
    }
    public void clear() { normal.clear(); light.clear(); }
    @Override public void close() { normal.close(); light.close(); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static int alpha(int color, double factor) { return ((int)Math.min(255, (color >>> 24) * Math.max(0, factor)) << 24) | (color & 0xffffff); }
    private static final class Batch implements AutoCloseable {
        ByteBuffer bytes;
        MappableRingBuffer gpu;
        int vertices;
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        void ensure(int capacity) {
            if (bytes != null && bytes.capacity() >= capacity) { bytes.clear(); return; }
            close();
            int size = Math.max(65536, Integer.highestOneBit(capacity - 1) << 1);
            bytes = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
        }
        void clear() { vertices = 0; if (bytes != null) bytes.clear(); }
        void finish() { if (bytes != null) bytes.flip(); }
        void upload() {
            if (vertices == 0) return;
            if (gpu == null || gpu.size() < bytes.remaining()) {
                if (gpu != null) gpu.close();
                gpu = new MappableRingBuffer(() -> "Scrimmy hit particle vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes.capacity());
            }
            try (var mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(gpu.currentBuffer().slice(0, bytes.remaining()), false, true)) {
                mapped.data().put(bytes);
                bytes.rewind();
            }
            var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            indices = sequential.getBuffer(vertices / 4 * 6); indexType = sequential.type();
        }
        void draw(com.mojang.blaze3d.systems.RenderPass pass, RenderPipeline pipeline) {
            if (vertices == 0) return;
            int count = vertices / 4 * 6;
            pass.setPipeline(pipeline); pass.setVertexBuffer(0, gpu.currentBuffer()); pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0, 0, count, 1);
        }
        void rotate() { if (vertices > 0) gpu.rotate(); }
        @Override public void close() {
            if (gpu != null) gpu.close(); gpu = null;
            if (bytes != null) MemoryUtil.memFree(bytes); bytes = null; vertices = 0;
        }
    }
}
