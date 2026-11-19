package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.scrimmy.client.ghost.GhostMath;
import com.scrimmy.client.ghost.LogoutGhostTracker;
import com.scrimmy.client.ghost.LogoutGhostTracker.Ghost;
import com.scrimmy.client.module.visual.LogoutGhosts;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** World ribbons follow Scrimmy's ESP path; projected heads reuse AntiVanish's vanilla PIP renderer. */
public final class LogoutGhostRenderer implements AutoCloseable {
    private static final double BOB_HEIGHT = .08, BOB_SPEED = 1.4, PULSE_SPEED = 1.8, PULSE_DEPTH = .16;
    private static final float BODY_WIDTH = 1.4f, BODY_GLOW_WIDTH = 5, BEAM_WIDTH = .9f, BEAM_GLOW_WIDTH = 4;
    private static final float BEAM_HEIGHT = 3, LABEL_HEIGHT = .3f;
    private static final double BODY_FILL = .035, GLOW_ALPHA = .13;
    private static final float CLIP_NEAR = .06f;
    private static final int MAX_VERTICES_PER_GHOST = 128, HEAD_TEXTURE_SIZE = 64;
    private static final float HEAD_MIN_SIZE = 16, HEAD_MAX_SIZE = 48, HEAD_DISTANCE_SCALE = 320, HEAD_PREVIEW_DISTANCE = 2.5f;
    private static final float HEAD_SPIN_SPEED = .7f, HEAD_PITCH = .12f, HEAD_GAP = 4;
    private static final float LABEL_MIN_SCALE = .8f, LABEL_MAX_SCALE = 1.25f, LABEL_DISTANCE_SCALE = 32, LABEL_SCREEN_FRACTION = .92f;
    private static final int LABEL_PADDING = 6, LABEL_LINE_HEIGHT = 11, PANEL_RADIUS = 5, MIN_ALPHA = 8;
    private static final int PANEL_COLOR = 0xD0192936, PANEL_SHADOW = 0x40000000, PANEL_BORDER_ALPHA = 100;
    private static final Supplier<String> PASS_NAME = () -> "Scrimmy LogoutGhosts";
    private static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2).build();
    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", "pipeline/logout_ghosts"))
        .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/block_esp"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withCull(false).withDepthWrite(false)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withBlend(BlendFunction.TRANSLUCENT).build());
    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};
    private static final int[][] FACES = {{0,2,3,1},{4,5,7,6},{0,1,5,4},{2,6,7,3},{0,4,6,2},{1,3,7,5}};
    private final LogoutGhosts module;
    private final Visual[] visuals = new Visual[LogoutGhostTracker.CAPACITY], projected = new Visual[LogoutGhostTracker.CAPACITY];
    private final long[] order = new long[LogoutGhostTracker.CAPACITY];
    private final float[] corners = new float[24];
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f(), identity = new Matrix4f();
    private final Vector4f clip = new Vector4f(), viewportUniform = new Vector4f();
    private final Vector3f zero = new Vector3f();
    private ScreenRectangle viewport;
    private int screenWidth, screenHeight, vertices, projectedCount;
    private ByteBuffer bytes;
    private MappableRingBuffer gpu;

    public LogoutGhostRenderer(LogoutGhosts module) {
        this.module = module;
        for (int i = 0; i < visuals.length; i++) visuals[i] = new Visual();
    }
    public void initialize() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "logout_ghosts"), (graphics, delta) -> hud(graphics));
    }
    /** Text, dimensions, and immutable viewport rectangles are refreshed on ticks, never per frame. */
    public void tick(Minecraft mc) {
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (viewport == null || width != screenWidth || height != screenHeight) {
            screenWidth = width; screenHeight = height; viewport = new ScreenRectangle(0, 0, width, height);
        }
        long now = System.nanoTime();
        for (int i = 0; i < module.tracker.size(); i++) {
            Ghost ghost = module.tracker.get(i);
            Visual v = visuals[ghost.slot];
            long seconds = (long)ghost.age(now);
            double dx = ghost.x - mc.player.getX(), dy = ghost.y - mc.player.getY(), dz = ghost.z - mc.player.getZ();
            long distance = Math.round(Math.sqrt(dx*dx + dy*dy + dz*dz));
            if (v.born == ghost.createdNanos && v.seconds == seconds && v.distance == distance && v.coordinates == module.showCoordinates.get()) continue;
            v.born = ghost.createdNanos; v.seconds = seconds; v.distance = distance; v.coordinates = module.showCoordinates.get();
            v.name = ghost.name; v.texture = ghost.skin.body().texturePath();
            v.status = ghost.status.label + " · " + seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60 + " ago";
            v.detail = distance + " blocks";
            v.position = Math.round(ghost.x) + ", " + Math.round(ghost.y) + ", " + Math.round(ghost.z);
            v.nameWidth = ClientFonts.width(v.name); v.statusWidth = ClientFonts.width(v.status);
            v.detailWidth = ClientFonts.width(v.detail); v.positionWidth = ClientFonts.width(v.position);
            v.width = Math.max(v.nameWidth, Math.max(v.statusWidth, v.detailWidth));
            if (v.coordinates) v.width = Math.max(v.width, v.positionWidth);
            v.height = LABEL_PADDING * 2 + LABEL_LINE_HEIGHT * (v.coordinates ? 4 : 3);
        }
    }
    public void extract(WorldExtractionContext context) {
        if (!module.enabled()) return;
        vertices = projectedCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!module.inWorld(mc) || mc.options.hideGui || module.tracker.size() == 0 || viewport == null) return;
        if (bytes == null) bytes = MemoryUtil.memAlloc(LogoutGhostTracker.CAPACITY * MAX_VERTICES_PER_GHOST * FORMAT.getVertexSize()).order(ByteOrder.nativeOrder());
        bytes.clear(); view.set(context.viewMatrix()); projection.set(context.cullProjectionMatrix()).mul(view);
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
        long now = System.nanoTime();
        double seconds = now / 1_000_000_000.0;
        int count = 0;
        for (int i = 0; i < module.tracker.size(); i++) {
            Ghost ghost = module.tracker.get(i);
            if (!ghost.dimension.equals(mc.level.dimension())) continue;
            double dx = ghost.x - cx, dy = ghost.y - cy, dz = ghost.z - cz;
            double squared = dx*dx + dy*dy + dz*dz;
            if (squared > module.maxDrawDistance.get() * module.maxDrawDistance.get()) continue;
            order[count++] = ((long)Float.floatToRawIntBits((float)squared) << 32) | i;
        }
        Arrays.sort(order, 0, count);
        // The same fixed arrays serve every frame. Far-to-near blending keeps nearby ghosts crisp.
        for (int j = count - 1; j >= 0; j--) {
            Ghost ghost = module.tracker.get((int)order[j]);
            double distance = Math.sqrt(Float.intBitsToFloat((int)(order[j] >>> 32))), age = ghost.age(now);
            double opacity = GhostMath.fade(age, module.duration.get(), distance, LogoutGhosts.FADE_DISTANCE, module.maxDrawDistance.get());
            if (opacity * (LogoutGhosts.GHOST_COLOR >>> 24) < MIN_ALPHA) continue;
            double phase = seconds * BOB_SPEED + ghost.slot;
            float x = (float)(ghost.x - cx), y = (float)(ghost.y - cy + BOB_HEIGHT * Math.sin(phase)), z = (float)(ghost.z - cz);
            double pulse = 1 - PULSE_DEPTH * (.5 + .5 * Math.sin(seconds * PULSE_SPEED + ghost.slot));
            int color = GhostMath.color(LogoutGhosts.GHOST_COLOR, age / module.duration.get(), opacity * pulse);
            if (LogoutGhosts.SHOW_BODY) body(x, y, z, ghost.width, ghost.height, color);
            if (LogoutGhosts.SHOW_BEAM) {
                line(x, y + ghost.height, z, x, y + ghost.height + BEAM_HEIGHT, z, alpha(color, GLOW_ALPHA), BEAM_GLOW_WIDTH);
                line(x, y + ghost.height, z, x, y + ghost.height + BEAM_HEIGHT, z, color, BEAM_WIDTH);
            }
            if (!(LogoutGhosts.SHOW_HEAD || LogoutGhosts.SHOW_LABEL) || !GhostMath.project(projection, x, y + ghost.height + LABEL_HEIGHT, z, clip)) continue;
            Visual v = visuals[ghost.slot];
            if (v.texture == null || v.born != ghost.createdNanos) continue;
            v.x = (clip.x / clip.w + 1) * screenWidth / 2;
            v.y = (1 - clip.y / clip.w) * screenHeight / 2;
            v.opacity = (float)(opacity * (LogoutGhosts.GHOST_COLOR >>> 24) / 255.0);
            v.color = GhostMath.color(LogoutGhosts.GHOST_COLOR, age / module.duration.get(), opacity);
            v.HEAD_SIZE = (float)Math.max(HEAD_MIN_SIZE, Math.min(HEAD_MAX_SIZE, HEAD_DISTANCE_SCALE / Math.max(1, distance))) * (float) LogoutGhosts.HEAD_SIZE;
            v.scale = (float)Math.max(LABEL_MIN_SCALE, Math.min(LABEL_MAX_SCALE, LABEL_DISTANCE_SCALE / Math.max(1, distance))) * (float) LogoutGhosts.LABEL_SIZE;
            v.scale = Math.min(v.scale, screenWidth * LABEL_SCREEN_FRACTION / (v.width + LABEL_PADDING * 2));
            v.head.marker(v.texture, v.x, v.y - HEAD_GAP - v.HEAD_SIZE / 2, v.HEAD_SIZE,
                (float)(age * HEAD_SPIN_SPEED - Math.toRadians(ghost.yaw)), HEAD_PITCH, v.opacity, viewport);
            projected[projectedCount++] = v;
        }
        bytes.flip();
    }
    private void body(float x, float y, float z, float width, float height, int color) {
        for (int i = 0; i < 8; i++) {
            corners[i*3] = x + ((i & 1) == 0 ? -width / 2 : width / 2);
            corners[i*3+1] = y + ((i & 2) == 0 ? 0 : height);
            corners[i*3+2] = z + ((i & 4) == 0 ? -width / 2 : width / 2);
        }
        int fill = alpha(color, BODY_FILL);
        for (int[] face : FACES) for (int c : face) vertex(corners[c*3], corners[c*3+1], corners[c*3+2], fill, 0, 0, 0, 0, 0);
        for (int[] edge : EDGES) {
            int a = edge[0]*3, b = edge[1]*3;
            line(corners[a], corners[a+1], corners[a+2], corners[b], corners[b+1], corners[b+2], alpha(color, GLOW_ALPHA), BODY_GLOW_WIDTH);
            line(corners[a], corners[a+1], corners[a+2], corners[b], corners[b+1], corners[b+2], color, BODY_WIDTH);
        }
    }
    private void line(float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        float za = view.m02()*ax + view.m12()*ay + view.m22()*az + view.m32();
        float zb = view.m02()*bx + view.m12()*by + view.m22()*bz + view.m32();
        if (za > -CLIP_NEAR && zb > -CLIP_NEAR) return;
        if (za > -CLIP_NEAR) { float t = (-CLIP_NEAR - za) / (zb - za); ax += (bx-ax)*t; ay += (by-ay)*t; az += (bz-az)*t; }
        else if (zb > -CLIP_NEAR) { float t = (-CLIP_NEAR - zb) / (za - zb); bx += (ax-bx)*t; by += (ay-by)*t; bz += (az-bz)*t; }
        vertex(ax,ay,az,color,bx,by,bz,-1000,width); vertex(ax,ay,az,color,bx,by,bz,1000,width);
        vertex(bx,by,bz,color,ax,ay,az,-1000,-width); vertex(bx,by,bz,color,ax,ay,az,1000,-width);
    }
    private void vertex(float x, float y, float z, int color, float otherX, float otherY, float otherZ, int side, float width) {
        bytes.putFloat(x).putFloat(y).putFloat(z).put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)(color>>>24));
        bytes.putFloat(otherX).putFloat(otherY);
        int bits = Float.floatToRawIntBits(otherZ);
        bytes.putShort((short)bits).putShort((short)(bits>>>16)).putShort((short)side).putShort((short)(width*100)); vertices++;
    }
    public void draw(WorldRenderContext context) {
        if (!module.enabled() || vertices == 0 || !module.inWorld(Minecraft.getInstance())) return;
        if (gpu == null) gpu = new MappableRingBuffer(PASS_NAME, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes.capacity());
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mapped = encoder.mapBuffer(gpu.currentBuffer().slice(0, bytes.remaining()), false, true)) { mapped.data().put(bytes); bytes.rewind(); }
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(vertices / 4 * 6);
        var target = Minecraft.getInstance().getMainRenderTarget();
        viewportUniform.set(target.width, target.height, 1, 1);
        var uniforms = RenderSystem.getDynamicUniforms().writeTransform(view, viewportUniform, zero, identity);
        // No depth attachment and no depth writes: other modules' world depth stays untouched.
        try (var pass = encoder.createRenderPass(PASS_NAME, target.getColorTextureView(), OptionalInt.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE); RenderSystem.bindDefaultUniforms(pass); pass.setUniform("DynamicTransforms", uniforms);
            pass.setVertexBuffer(0, gpu.currentBuffer()); pass.setIndexBuffer(indices, sequential.type());
            pass.drawIndexed(0, 0, vertices / 4 * 6, 1);
        }
        gpu.rotate();
    }
    private void hud(GuiGraphics g) {
        if (!module.enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!module.inWorld(mc) || mc.options.hideGui || g.guiWidth() != screenWidth || g.guiHeight() != screenHeight) return;
        for (int i = 0; i < projectedCount; i++) {
            Visual v = projected[i];
            if (LogoutGhosts.SHOW_HEAD) g.guiRenderState.submitPicturesInPictureState(v.head);
            if (!LogoutGhosts.SHOW_LABEL) continue;
            v.nameWidth = ClientFonts.width(v.name); v.statusWidth = ClientFonts.width(v.status);
            v.detailWidth = ClientFonts.width(v.detail); v.positionWidth = ClientFonts.width(v.position);
            v.width = Math.max(v.nameWidth, Math.max(v.statusWidth, v.detailWidth));
            if (v.coordinates) v.width = Math.max(v.width, v.positionWidth);
            float half = (v.width / 2f + LABEL_PADDING) * v.scale;
            float x = Math.max(half, Math.min(screenWidth - half, v.x));
            float y = Math.max(0, Math.min(screenHeight - v.height * v.scale, v.y));
            g.pose().pushMatrix();
            g.pose().translate(x, y); g.pose().scale(v.scale, v.scale);
            v.panel.draw(g, v.width + LABEL_PADDING * 2, v.height, PANEL_RADIUS,
                alpha(PANEL_COLOR, v.opacity), alpha((PANEL_BORDER_ALPHA << 24) | (v.color & 0xffffff), v.opacity), alpha(PANEL_SHADOW, v.opacity), viewport);
            int textColor = (Math.min(255, (int)(255 * v.opacity)) << 24) | (v.color & 0xffffff);
            ClientFonts.draw(g, v.name, -ClientFonts.widthExact(v.name) / 2f, LABEL_PADDING, textColor);
            ClientFonts.draw(g, v.status, -ClientFonts.widthExact(v.status) / 2f, LABEL_PADDING + LABEL_LINE_HEIGHT, textColor);
            ClientFonts.draw(g, v.detail, -ClientFonts.widthExact(v.detail) / 2f, LABEL_PADDING + LABEL_LINE_HEIGHT * 2, textColor);
            if (v.coordinates) ClientFonts.draw(g, v.position, -ClientFonts.widthExact(v.position) / 2f, LABEL_PADDING + LABEL_LINE_HEIGHT * 3, textColor);
            g.pose().popMatrix();
        }
    }
    private static int alpha(int color, double factor) { return ((int)((color >>> 24) * factor) << 24) | (color & 0xffffff); }
    public void clear() { vertices = projectedCount = 0; }
    @Override public void close() {
        clear();
        if (gpu != null) gpu.close(); gpu = null;
        if (bytes != null) MemoryUtil.memFree(bytes); bytes = null;
    }
    private static final class Visual {
        final AntiVanishHeadRenderer.HeadState head = new AntiVanishHeadRenderer.HeadState(null, HEAD_TEXTURE_SIZE, HEAD_TEXTURE_SIZE, 1, HEAD_PREVIEW_DISTANCE, 0, HEAD_PITCH, 0, null);
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
        Identifier texture;
        String name, status, detail, position;
        long born = -1, seconds, distance;
        boolean coordinates;
        int width, height, color, nameWidth, statusWidth, detailWidth, positionWidth;
        float x, y, opacity, scale, HEAD_SIZE;
    }
}
