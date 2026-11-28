package com.scrimmy.client.render;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.render.TextureSetup;
import java.util.IdentityHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import com.mojang.blaze3d.textures.TextureFormat;
import com.scrimmy.client.font.FontFace;
import com.scrimmy.client.mixin.FontAccessor;
import com.scrimmy.client.theme.FontCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Font-only SDF pipeline. CPU atlases bake off-thread; GPU uploads are amortized one page/frame.
 * Viewport changes keep the old SDF generation visible until every replacement family is ready. */
public final class SdfFonts {
    public static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXT_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("scrimmyclient", "pipeline/sdf_text"))
        .withVertexShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/sdf_text"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("scrimmyclient", "core/sdf_text"))
        .withSampler("Sampler0").withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA).withDepthWrite(false).build());
    private static final int PAGE_SIZE = 1024, GUTTER = 2;
    private static final ExecutorService BAKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Scrimmy font atlas"); thread.setDaemon(true); return thread;
    });
    private static final List<FontCatalog.Family> CATALOG = FontCatalog.entries();
    private static final Map<String, Source> SOURCES = new HashMap<>();
    private static final LinkedHashMap<Viewport, Generation> CACHE = new LinkedHashMap<>();
    private static final Map<GpuTextureView, TextureSetup> TEXTURES = new IdentityHashMap<>();
    private static volatile long requestVersion;
    private static FontFace[] faces;
    private static Generation active, uploading;
    private static CompletableFuture<Generation> pending;
    private static Viewport requested;
    private static int uploadIndex;
    private static GlyphRenderTypes renderTypes;
    static {
        for (int i = 0; i < CATALOG.size(); i++) {
            SOURCES.put(CATALOG.get(i).resource() + "_regular", new Source(i * 2));
            SOURCES.put(CATALOG.get(i).resource() + "_semibold", new Source(i * 2 + 1));
        }
    }
    private SdfFonts() {}
    public static TextureSetup textureSetup(GpuTextureView view) { return TEXTURES.get(view); }
    public static boolean owns(FontDescription description) {
        return description instanceof FontDescription.Resource resource && resource.id().getNamespace().equals("scrimmyclient")
            && SOURCES.containsKey(resource.id().getPath());
    }
    public static GlyphSource source(FontDescription description) {
        if (active == null || !owns(description)) return null;
        return SOURCES.get(((FontDescription.Resource)description).id().getPath());
    }
    public static float kerning(int family, boolean bold, int a, int b) {
        return faces == null ? 0 : faces[family * 2 + (bold ? 1 : 0)].kerning(a, b);
    }
    public static void reload() {
        requestVersion++;
        if (pending != null) pending.cancel(true);
        if (uploading != null) uploading.close();
        uploading = null; pending = null;
        for (Generation generation : CACHE.values()) generation.close();
        CACHE.clear(); active = null; requested = null;
        Font[] inter = new Font[2];
        FontCatalog.Family fallback = CATALOG.stream().filter(f -> f.name().equals("Inter")).findFirst().orElseThrow();
        inter[0] = read(fallback.regular()); inter[1] = read(fallback.semibold());
        faces = new FontFace[CATALOG.size() * 2];
        for (int i = 0; i < CATALOG.size(); i++) {
            var entry = CATALOG.get(i);
            Font regular = read(entry.regular());
            faces[i * 2] = new FontFace(regular, inter[0], false);
            faces[i * 2 + 1] = new FontFace(entry.semibold() == null ? regular : read(entry.semibold()), inter[1], entry.semibold() == null);
        }
        var vanilla = GlyphRenderTypes.createForIntensityTexture(Identifier.fromNamespaceAndPath("scrimmyclient", "sdf_font"));
        renderTypes = new GlyphRenderTypes(vanilla.normal(), vanilla.seeThrough(), vanilla.polygonOffset(), PIPELINE);
        frame();
    }
    private static Font read(String path) {
        try (var stream = SdfFonts.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing bundled font: " + path);
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (IOException | java.awt.FontFormatException e) {
            throw new IllegalStateException("Could not read font " + path, e);
        }
    }
    public static void frame() {
        if (faces == null) return;
        var window = Minecraft.getInstance().getWindow();
        int width = window.getWidth(), height = window.getHeight(), scale = window.getGuiScale();
        int clientScale = (int)Math.round(com.scrimmy.client.ScrimmyClient.THEME.guiScale.get() * 100);
        if (requested == null || requested.width != width || requested.height != height || requested.scale != scale || requested.clientScale != clientScale) {
            requested = new Viewport(width, height, scale, clientScale);
            requestVersion++;
            if (pending != null) pending.cancel(true);
            if (uploading != null) uploading.close();
            uploading = null; uploadIndex = 0;
            Generation cached = CACHE.get(requested);
            if (cached != null) { active = cached; pending = null; }
            else {
                Viewport viewport = requested;
                FontFace[] snapshot = faces;
                long version = requestVersion;
                pending = CompletableFuture.supplyAsync(() -> build(viewport, snapshot, version), BAKER);
            }
        }
        if (pending != null && pending.isDone()) {
            if (pending.isCompletedExceptionally() || pending.isCancelled()) {
                LoggerFactory.getLogger("ScrimmyClient").warn("Font atlas preparation failed; keeping the previous generation");
                pending = null; return;
            }
            uploading = pending.join(); pending = null; uploadIndex = 0;
        }
        if (uploading == null) return;
        // A page is 1 MiB (single channel). Never stall a font switch with a whole-family upload.
        if (uploadIndex < uploading.pages.size()) uploading.pages.get(uploadIndex++).upload();
        if (uploadIndex == uploading.pages.size()) {
            active = uploading; CACHE.put(requested, uploading); uploading = null;
            ClientFonts.atlasReady();
            while (CACHE.size() > 2) {
                var iterator = CACHE.entrySet().iterator(); var first = iterator.next(); iterator.remove();
                first.getValue().close();
            }
        }
    }
    private static Generation build(Viewport viewport, FontFace[] faces, long version) {
        Generation generation = new Generation(viewport, faces);
        for (int source = 0; source < faces.length; source++) {
            for (int codepoint = 32; codepoint <= 126; codepoint++) {
                if (version != requestVersion || Thread.currentThread().isInterrupted()) return generation;
                generation.raster(source, codepoint);
            }
            for (int cp : new int[]{0xa0, 0xb0, 0xb7, 0xd7, 0x2013, 0x2014, 0x2026, 0xfffd}) generation.raster(source, cp);
        }
        return generation;
    }
    public static void close() {
        requestVersion++;
        if (pending != null) pending.cancel(true);
        if (uploading != null) uploading.close();
        for (Generation generation : CACHE.values()) generation.close();
        CACHE.clear(); active = uploading = null; faces = null; BAKER.shutdownNow();
    }
    private record Viewport(int width, int height, int scale, int clientScale) {
        int rasterScale() { return Math.clamp((int)Math.ceil(scale * 3 * clientScale / 100.0), 2, 36); }
    }
    private static final class Source implements GlyphSource {
        final int index;
        Source(int index) { this.index = index; }
        @Override public BakedGlyph getGlyph(int codepoint) {
            Generation generation = active;
            if (!generation.faces[index].supports(codepoint))
                return ((FontAccessor)Minecraft.getInstance().font).scrimmy$provider().glyphs(FontDescription.DEFAULT).getGlyph(codepoint);
            BakedGlyph glyph = generation.baked[index].get(codepoint);
            if (glyph != null) return glyph;
            CpuGlyph cpu = generation.raster(index, codepoint);
            if (cpu.raster.width() == 0) glyph = new Empty(cpu.raster.advance());
            else {
                cpu.page.upload();
                float factor = generation.viewport.rasterScale();
                var r = cpu.raster;
                glyph = new BakedSheetGlyph(GlyphInfo.simple(r.advance()), renderTypes, cpu.page.view,
                    cpu.x / (float)PAGE_SIZE, (cpu.x + r.width()) / (float)PAGE_SIZE,
                    cpu.y / (float)PAGE_SIZE, (cpu.y + r.height()) / (float)PAGE_SIZE,
                    r.left(), r.left() + r.width() / factor, r.top(), r.top() + r.height() / factor);
            }
            generation.baked[index].put(codepoint, glyph);
            return glyph;
        }
        @Override public BakedGlyph getRandomGlyph(RandomSource random, int width) { return getGlyph('?' ); }
    }
    private static final class Empty implements BakedGlyph {
        private final GlyphInfo info;
        Empty(float advance) { info = GlyphInfo.simple(advance); }
        @Override public GlyphInfo info() { return info; }
        @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadow, Style style, float boldOffset, float shadowOffset) { return null; }
    }
    private record CpuGlyph(FontFace.Raster raster, Page page, int x, int y) {}
    private static final class Generation implements AutoCloseable {
        final Viewport viewport;
        final FontFace[] faces;
        final List<Page> pages = new ArrayList<>();
        final List<Int2ObjectMap<CpuGlyph>> rasters = new ArrayList<>();
        final Int2ObjectMap<BakedGlyph>[] baked;
        @SuppressWarnings("unchecked") Generation(Viewport viewport, FontFace[] faces) {
            this.viewport = viewport; this.faces = faces;
            baked = (Int2ObjectMap<BakedGlyph>[])new Int2ObjectMap<?>[faces.length];
            for (int i = 0; i < faces.length; i++) { rasters.add(new Int2ObjectOpenHashMap<>()); baked[i] = new Int2ObjectOpenHashMap<>(); }
        }
        CpuGlyph raster(int source, int codepoint) {
            Int2ObjectMap<CpuGlyph> cache = rasters.get(source);
            CpuGlyph glyph = cache.get(codepoint);
            if (glyph != null) return glyph;
            var raster = faces[source].rasterize(codepoint, viewport.rasterScale());
            Page page = null; int x = 0, y = 0;
            if (raster.width() > 0) {
                if (raster.width() + GUTTER * 2 > PAGE_SIZE || raster.height() + GUTTER * 2 > PAGE_SIZE)
                    throw new IllegalArgumentException("Glyph is too large for the font atlas");
                if (!pages.isEmpty()) page = pages.getLast();
                if (page == null || !page.place(raster.width(), raster.height())) {
                    page = new Page(); pages.add(page); page.place(raster.width(), raster.height());
                }
                x = page.placedX; y = page.placedY;
                for (int row = 0; row < raster.height(); row++)
                    System.arraycopy(raster.pixels(), row * raster.width(), page.pixels, (y + row) * PAGE_SIZE + x, raster.width());
                page.dirty = true;
            }
            glyph = new CpuGlyph(raster, page, x, y); cache.put(codepoint, glyph); return glyph;
        }
        @Override public void close() { for (Page page : pages) page.close(); }
    }
    private static final class Page implements AutoCloseable {
        final byte[] pixels = new byte[PAGE_SIZE * PAGE_SIZE];
        GpuTexture texture;
        GpuTextureView view;
        int x = GUTTER, y = GUTTER, rowHeight, placedX, placedY;
        boolean dirty;
        boolean place(int width, int height) {
            if (x + width + GUTTER > PAGE_SIZE) { x = GUTTER; y += rowHeight + GUTTER; rowHeight = 0; }
            if (y + height + GUTTER > PAGE_SIZE) return false;
            placedX = x; placedY = y; x += width + GUTTER; rowHeight = Math.max(rowHeight, height); return true;
        }
        void upload() {
            if (!dirty && texture != null) return;
            if (texture == null) {
                texture = RenderSystem.getDevice().createTexture("Scrimmy SDF glyph atlas", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RED8, PAGE_SIZE, PAGE_SIZE, 1, 1);
                view = RenderSystem.getDevice().createTextureView(texture);
                TEXTURES.put(view, TextureSetup.singleTextureWithLightmap(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
            }
            ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length);
            try {
                buffer.put(pixels).flip();
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, buffer, NativeImage.Format.LUMINANCE, 0, 0, 0, 0, PAGE_SIZE, PAGE_SIZE);
            } finally { MemoryUtil.memFree(buffer); }
            dirty = false;
        }
        @Override public void close() { if (view != null) { TEXTURES.remove(view); view.close(); } if (texture != null) texture.close(); }
    }
}
