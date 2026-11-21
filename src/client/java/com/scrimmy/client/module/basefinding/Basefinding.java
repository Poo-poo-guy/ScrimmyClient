package com.scrimmy.client.module.basefinding;

import com.scrimmy.client.basefinding.*;
import com.scrimmy.client.basefinding.DetectionLog.Detection;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.ChunkFinderRenderer;
import com.scrimmy.client.setting.*;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** Keeps historical geodes separate from the live, relative base-area estimate. */
public final class Basefinding extends Module {
    public static final int SCORE_INTERVAL_TICKS = 10;
    public final BooleanSetting showGeodes = add(new BooleanSetting("cfShowGeodes", "Show Geodes", true));
    public final BooleanSetting highlightChunks = add(new BooleanSetting("cfHighlightChunks", "Highlight Chunks", true));
    public final BooleanSetting nametags = add(new BooleanSetting("cfLabels", "Nametags", true));
    public final NumberSetting threshold = add(new NumberSetting("cfThreshold", "Minimum Score", 60, 0, 100, 1));
    public final NumberSetting range = add(new NumberSetting("cfRange", "Range", 16, 2, 32, 1));
    private final LinkedHashMap<Long, Detection> detections = new LinkedHashMap<>();
    private final Map<Long, List<GeodeAnalysis.Geode>> live = new HashMap<>();
    private final ExecutorService scoring = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Scrimmy Base likelihood"); t.setDaemon(true); return t;
    });
    private final Long2DoubleOpenHashMap chunkScores = new Long2DoubleOpenHashMap();
    private List<BaseLikelihood.Chunk> chunks = List.of();
    private List<GeodeAnalysis.Geode> geodes = List.of();
    private Future<List<BaseLikelihood.Chunk>> estimate;
    private DetectionLog log;
    public ChunkFinderScanner scanner;
    public ChunkFinderRenderer renderer;
    private ClientLevel world;
    private String server, dimension;
    private CompletableFuture<List<Detection>> loading;
    private boolean dirty = true;
    private int ticks, centerX = Integer.MIN_VALUE, centerZ;

    public Basefinding() {
        super("ChunkFinder", "Estimate a base area from amethyst growth in loaded chunks", Category.BASEFINDING);
        showGeodes.tooltip("Mark the amethyst geodes found nearby.");
        highlightChunks.tooltip("Shade chunks where a base is most likely.");
        nametags.tooltip("Show a floating base chance score above each highlighted chunk.");
        threshold.tooltip("Hide chunks with a lower base chance score.");
        range.tooltip("Include this many chunks around you; only loaded chunks can be scanned.");
    }
    public boolean active() { return enabled() && world != null && world.dimension().equals(Level.OVERWORLD); }
    public ClientLevel world() { return world; }
    public boolean ready() { return world != null && server != null && loading == null; }
    public Collection<Detection> detections() { return Collections.unmodifiableCollection(detections.values()); }
    public List<BaseLikelihood.Chunk> chunks() { return chunks; }
    /** Relative score for optional consumers; zero while disabled or outside the live grid. */
    public double scoreAtChunk(int x, int z) { return active() ? chunkScores.get(BaseLikelihood.key(x, z)) : 0; }
    public List<GeodeAnalysis.Geode> geodes() { return geodes; }

    @Override public void initialize() {
        log = new DetectionLog(FabricLoader.getInstance().getConfigDir().resolve("scrimmy/basefinding"));
        scanner = new ChunkFinderScanner(this); scanner.initialize();
        renderer = new ChunkFinderRenderer(this); renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> { if (chunk != null) scanner.load(level, chunk); });
        ClientChunkEvents.CHUNK_UNLOAD.register(scanner::unload);
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc, level) -> session(level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> session(null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            session(null); scanner.close(); renderer.close(); log.close(); scoring.shutdownNow();
        });
        session(Minecraft.getInstance().level);
    }
    private void clearLive() {
        if (estimate != null) estimate.cancel(true);
        estimate = null; live.clear(); chunkScores.clear(); geodes = List.of(); chunks = List.of();
        dirty = true; centerX = Integer.MIN_VALUE;
    }
    private void session(ClientLevel next) {
        if (next == world) return;
        if (log != null) log.flush();
        if (scanner != null) scanner.reset(next);
        if (renderer != null) renderer.clear();
        clearLive(); world = next; detections.clear(); loading = null; server = dimension = null;
        if (next == null || !next.dimension().equals(Level.OVERWORLD) || log == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null)
            server = "singleplayer:" + mc.getSingleplayerServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        else if (mc.getCurrentServer() != null) server = "multiplayer:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        else return;
        dimension = next.dimension().identifier().toString();
        loading = log.load(server, dimension);
    }
    @Override protected void onEnable() { clearLive(); if (scanner != null) scanner.reset(world); }
    @Override protected void onDisable() {
        clearLive(); if (scanner != null) scanner.reset(world);
        if (renderer != null) renderer.refresh();
    }
    @Override protected void onSettingChanged(Setting<?> s) {
        dirty = true;
        if (renderer != null) renderer.refresh();
    }
    @Override public void onTick(Minecraft mc) {
        if (mc.level != world) session(mc.level);
        if (loading != null && loading.isDone()) {
            try { for (Detection d : loading.join()) detections.putIfAbsent(d.key(), d); }
            catch (Exception e) { error("Could not load detections", e); }
            loading = null;
            save();
        }
        if (!active() || mc.player == null || mc.isPaused()) return;
        ticks++;
        int x = mc.player.blockPosition().getX() >> 4, z = mc.player.blockPosition().getZ() >> 4;
        if (x != centerX || z != centerZ) { centerX = x; centerZ = z; dirty = true; }
        if (estimate != null && estimate.isDone()) {
            try {
                chunks = estimate.get(); chunkScores.clear();
                for (var chunk : chunks) chunkScores.put(chunk.key(), chunk.score());
            }
            catch (CancellationException ignored) { }
            catch (Exception e) { error("Base likelihood failed", e); }
            estimate = null; renderer.refresh();
        }
        scanner.tick(mc);
        if (ticks % SCORE_INTERVAL_TICKS != 0) return;
        if (dirty && estimate == null) {
            Map<Long, GeodeAnalysis.Geode> unique = new LinkedHashMap<>();
            int evidenceRange = range.get().intValue() + BaseLikelihood.LOADED_RADIUS;
            for (var entry : live.entrySet()) {
                int cx = (int)(long)entry.getKey(), cz = (int)(entry.getKey() >>> 32);
                if (Math.abs(cx - x) > evidenceRange || Math.abs(cz - z) > evidenceRange) continue;
                for (var g : entry.getValue()) unique.put(g.id(), g);
            }
            geodes = List.copyOf(unique.values());
            List<GeodeAnalysis.Geode> snapshot = geodes;
            int r = range.get().intValue();
            estimate = scoring.submit(() -> BaseLikelihood.estimate(snapshot, x, z, r));
            dirty = false;
        }
        renderer.refresh();
    }
    public void accept(int x, int z, List<GeodeAnalysis.Geode> found) {
        long key = Detection.key(x, z);
        List<GeodeAnalysis.Geode> previous = found.isEmpty() ? live.remove(key) : live.put(key, found);
        if (Objects.equals(previous, found) || previous == null && found.isEmpty()) return;
        dirty = true;
        if (found.isEmpty()) return;
        double growth = found.stream().mapToDouble(g -> BaseLikelihood.growth(g.stats()) * 100).max().orElse(0);
        if (growth < 60 && !detections.containsKey(key) || detections.size() >= DetectionLog.LIMIT && !detections.containsKey(key)) return;
        Detection old = detections.get(key);
        long now = System.currentTimeMillis();
        detections.put(key, new Detection(x, z, found, growth, old == null ? now : old.firstSeen(), now,
                old == null ? "" : old.notes(), old != null && old.checked(), old != null && old.falsePositive()));
        if (old == null) {
            String text = "Grown geode at " + (x * 16 + 8) + ", " + (z * 16 + 8);
            message(text);
            if (renderer != null) renderer.toast(text);
        }
        save();
    }
    public void unloaded(long key) { if (live.remove(key) != null) dirty = true; }
    private void save() { if (log != null && ready()) log.save(server, dimension, detections.values()); }
    public void remove(long key) { detections.remove(key); save(); }
    public void annotate(long key, String notes, boolean checked, boolean falsePositive) {
        Detection d = detections.get(key);
        if (d != null) { detections.put(key, d.annotation(notes, checked, falsePositive)); save(); }
    }
    public void clearDetections() { detections.clear(); save(); }
    public void export() {
        if (log == null || !ready()) return;
        log.export(server, dimension, detections.values()).whenComplete((path, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) error("Export failed", error); else message("Exported to " + path);
        }));
    }
    public void message(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(com.scrimmy.client.render.ClientFonts.component("[ChunkFinder] " + text), false);
    }
    public void error(String text, Throwable e) { LoggerFactory.getLogger("ScrimmyClient").error(text, e); }
}
