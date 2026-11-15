package com.scrimmy.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Vanilla manages the preview's color/depth textures, HUD placement, and resource lifetime. */
public final class AntiVanishHeadRenderer extends PictureInPictureRenderer<AntiVanishHeadRenderer.HeadState> {
    private static final int SKIN_SIZE = 64, FACE_SIZE = 8, HAT_U_OFFSET = 32;
    private static final int VERTICES_PER_CUBE = 24, INDICES_PER_CUBE = 36;
    private static final int BUFFER_BYTES = VERTICES_PER_CUBE * 2 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
    private static final RenderPipeline HEAD = pipeline(false, false), HAT = pipeline(true, false), GHOST = pipeline(false, true);
    // x points right, y points up, and +z is the face looking at the camera.
    // Each face goes top-left, bottom-left, bottom-right, top-right, counterclockwise from outside.
    static final float[][] FACE_CORNERS = {
        {-1,1,1, -1,-1,1, 1,-1,1, 1,1,1},     // Front (+z)
        {1,1,1, 1,-1,1, 1,-1,-1, 1,1,-1},     // Right (+x)
        {1,1,-1, 1,-1,-1, -1,-1,-1, -1,1,-1}, // Back (-z)
        {-1,1,-1, -1,-1,-1, -1,-1,1, -1,1,1}, // Left (-x)
        {-1,1,-1, -1,1,1, 1,1,1, 1,1,-1},     // Top (+y)
        {-1,-1,1, -1,-1,-1, 1,-1,-1, 1,-1,1}  // Bottom (-y)
    };
    static final int[][] FACE_UV = {{8,8}, {16,8}, {24,8}, {0,8}, {8,0}, {16,0}};
    private final Matrix4f transform = new Matrix4f(), identity = new Matrix4f();
    private final Vector4f white = new Vector4f(1);
    private final Vector3f zero = new Vector3f();
    private ByteBuffer bytes;
    private MappableRingBuffer gpu;

    public AntiVanishHeadRenderer(MultiBufferSource.BufferSource buffers) { super(buffers); }
    private static RenderPipeline pipeline(boolean hat, boolean ghost) {
        var builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", ghost ? "pipeline/logout_ghost_head" : hat ? "pipeline/anti_vanish_hat" : "pipeline/anti_vanish_head"))
            .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/anti_vanish_head"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/anti_vanish_head"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withCull(true).withDepthWrite(!hat).withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST);
        if (hat || ghost) builder.withBlend(BlendFunction.TRANSLUCENT);
        return RenderPipelines.register(builder.build());
    }
    @Override public Class<HeadState> getRenderStateClass() { return HeadState.class; }
    @Override protected String getTextureLabel() { return "Scrimmy AntiVanish head"; }
    @Override protected void renderToTexture(HeadState state, PoseStack unused) {
        if (bytes == null) {
            bytes = MemoryUtil.memAlloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
            cube(bytes, .5f, 0);
            cube(bytes, .5f * AntiVanishEffect.HEAD_HAT_SCALE, HAT_U_OFFSET);
            bytes.flip();
            gpu = new MappableRingBuffer(() -> "Scrimmy AntiVanish head vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, BUFFER_BYTES);
        }
        // Use our own perspective in this isolated preview. Never change the world's FOV or matrices.
        transform.setPerspective((float)Math.toRadians(AntiVanishEffect.HEAD_FOV_DEGREES), state.width() / (float)state.height(),
            AntiVanishEffect.HEAD_NEAR_PLANE, AntiVanishEffect.HEAD_FAR_PLANE)
            .translate(0, 0, -state.distance()).rotateZ(state.roll()).rotateX(state.pitch()).rotateY(state.yaw()).scale(state.size());
        // This shader uses ModelViewMat as a complete projection * view * model matrix.
        white.w = state.opacity();
        var uniforms = RenderSystem.getDynamicUniforms().writeTransform(transform, white, zero, identity);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mapped = encoder.mapBuffer(gpu.currentBuffer().slice(0, BUFFER_BYTES), false, true)) { mapped.data().put(bytes); bytes.rewind(); }
        var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = sequential.getBuffer(INDICES_PER_CUBE);
        var skin = Minecraft.getInstance().getTextureManager().getTexture(state.texture()).getTextureView();
        // These are the vanilla PIP targets, never the world's depth buffer. Walls cannot hide the head.
        try (var pass = encoder.createRenderPass(() -> "Scrimmy AntiVanish head", RenderSystem.outputColorTextureOverride, OptionalInt.empty(),
                RenderSystem.outputDepthTextureOverride, OptionalDouble.empty())) {
            pass.setPipeline(state.opacity() < 1 ? GHOST : HEAD);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.bindTexture("Sampler0", skin, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setVertexBuffer(0, gpu.currentBuffer()); pass.setIndexBuffer(indices, sequential.type());
            pass.drawIndexed(0, 0, INDICES_PER_CUBE, 1);
            // Draw the larger, translucent hat after the solid head, keeping its transparent pixels clear.
            pass.setPipeline(HAT);
            pass.drawIndexed(VERTICES_PER_CUBE, 0, INDICES_PER_CUBE, 1);
        }
        gpu.rotate();
    }
    static void cube(ByteBuffer bytes, float half, int uOffset) {
        for (int face = 0; face < FACE_CORNERS.length; face++) {
            float[] corners = FACE_CORNERS[face];
            for (int vertex = 0; vertex < 4; vertex++) {
                bytes.putFloat(corners[vertex * 3] * half).putFloat(corners[vertex * 3 + 1] * half).putFloat(corners[vertex * 3 + 2] * half);
                float u = FACE_UV[face][0] + uOffset + (vertex >= 2 ? FACE_SIZE : 0);
                boolean bottomRow = vertex == 1 || vertex == 2;
                // Vanilla's bottom square is flipped vertically relative to the other five faces.
                if (face == 5) bottomRow = !bottomRow;
                float v = FACE_UV[face][1] + (bottomRow ? FACE_SIZE : 0);
                bytes.putFloat(u / SKIN_SIZE).putFloat(v / SKIN_SIZE);
                bytes.put((byte)255).put((byte)255).put((byte)255).put((byte)255);
            }
        }
    }
    @Override public void close() {
        if (gpu != null) gpu.close(); gpu = null;
        if (bytes != null) MemoryUtil.memFree(bytes); bytes = null;
        super.close();
    }
    /** Pooled markers can move/scale this state without resizing their PIP texture every frame. */
    public static final class HeadState implements PictureInPictureRenderState {
        private Identifier texture;
        private final int width, height;
        private float size, distance, yaw, pitch, roll, opacity = 1;
        private final Matrix3x2f pose = new Matrix3x2f();
        private ScreenRectangle scissor, bounds;
        public HeadState(Identifier texture, int width, int height, float size, float distance, float yaw, float pitch, float roll, ScreenRectangle scissor) {
            this.texture = texture; this.width = width; this.height = height; this.size = size; this.distance = distance;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.scissor = scissor;
        }
        public void marker(Identifier texture, float x, float y, float displaySize, float yaw, float pitch, float opacity, ScreenRectangle viewport) {
            this.texture = texture; this.yaw = yaw; this.pitch = pitch; this.opacity = opacity;
            pose.identity().translate(x - displaySize / 2, y - displaySize / 2).scale(displaySize / width);
            scissor = bounds = viewport;
        }
        public Identifier texture() { return texture; }
        public int width() { return width; }
        public int height() { return height; }
        public float size() { return size; }
        public float distance() { return distance; }
        public float yaw() { return yaw; }
        public float pitch() { return pitch; }
        public float roll() { return roll; }
        public float opacity() { return opacity; }
        @Override public Matrix3x2f pose() { return pose; }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public int x0() { return 0; }
        @Override public int y0() { return 0; }
        @Override public int x1() { return width; }
        @Override public int y1() { return height; }
        @Override public float scale() { return 1; }
        @Override public ScreenRectangle bounds() { return bounds != null ? bounds : PictureInPictureRenderState.getBounds(x0(), y0(), x1(), y1(), scissor); }
    }
}
