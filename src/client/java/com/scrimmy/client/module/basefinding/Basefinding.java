package com.scrimmy.client.module.basefinding;

import com.scrimmy.client.basefinding.*;
import com.scrimmy.client.basefinding.DetectionLog.Detection;
import com.scrimmy.client.gui.BasefindingLogScreen;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.ChunkFinderRenderer;
import com.scrimmy.client.setting.*;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

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
import java.util.concurrent.CompletableFuture;

/**
 * Basefinding owns the shared log; ChunkFinder is a settings section, never a registered module.
 */
public final class Basefinding extends Module {
    public final BooleanSetting finder,
            manMade,
            neighboring,
            fill,
            outline,
            tracers,
            beams,
            labels,
            singleMode,
            pulse,
            chat,
            toast,
            debug;
    public final NumberSetting threshold,
            clusterWeight,
            fillWeight,
            anomalyWeight,
            madeWeight,
            neighborWeight,
            naturalFill,
            naturalClusters;
    public final NumberSetting manMadeRadius,
            spawnRadius,
            scanBudget,
            distance,
            minY,
            maxY,
            fillAlpha,
            outlineWidth,
            fade;
    public final ModeSetting minimumConfidence;
    public final ColorSetting lowColor,
            mediumColor,
            highColor,
            outlineColor,
            labelColor,
            singleColor,
            pulseA,
            pulseB;
    private final LinkedHashMap<Long, Detection> detections = new LinkedHashMap<>();
    private final Map<Long, List<GeodeAnalysis.Geode>> live = new HashMap<>();
    private final Map<Long, Long> observed = new HashMap<>();
    private final LinkedHashSet<Long> scoring = new LinkedHashSet<>();
    private DetectionLog log;
    public ChunkFinderScanner scanner;
    public ChunkFinderRenderer renderer;
    private ClientLevel world;
    private String server, dimension;
    private CompletableFuture<List<Detection>> loading;
    private boolean rescore;
    private int ticks;

    public Basefinding() {
        super(
                "Basefinding",
                "Remember possible bases from overgrown amethyst geodes",
                Category.BASEFINDING);
        group("chunkFinder", "ChunkFinder", true);
        finder = b("cfEnabled", "Enable ChunkFinder", true);
        finder.tooltip(
                "Overworld only. Analyzes chunks sent by the server within your render distance."
                    + " Growth suggests time loaded, not proof of a base.");
        threshold = n("cfThreshold", "Score threshold", 60, 0, 100, 1);
        minimumConfidence =
                add(
                        new ModeSetting(
                                "cfConfidence",
                                "Minimum confidence to display",
                                "Low",
                                "Low",
                                "Medium",
                                "High"));
        scanBudget = n("cfBudget", "Snapshot budget (ms/tick)", 1, .25, 4, .25);
        spawnRadius = n("cfSpawnRadius", "Spawn exclusion (blocks; 0=off)", 0, 0, 10000, 16);
        group("cfScoring", "ChunkFinder / Scoring weights", false);
        clusterWeight = n("cfClusterWeight", "Mature clusters", 60, 0, 100, 1);
        fillWeight = n("cfFillWeight", "Total fill", 15, 0, 100, 1);
        anomalyWeight = n("cfAnomalyWeight", "Worldgen anomaly", 15, 0, 100, 1);
        manMade = b("cfManMade", "Man-made evidence", true);
        manMadeRadius = n("cfManMadeRadius", "Evidence radius", 4, 1, 8, 1);
        madeWeight = n("cfMadeWeight", "Man-made bonus", 5, 0, 50, 1);
        neighboring = b("cfNeighboring", "Neighboring overgrown geodes", true);
        neighborWeight = n("cfNeighborWeight", "Neighbor bonus", 5, 0, 50, 1);
        naturalFill = n("cfNaturalFill", "Natural placements per budding block", .35, 0, 1, .001);
        naturalFill.tooltip(
                "Vanilla 1.21.11: .35 chance per budding block; only its first growable face is"
                    + " filled. Scoring adjusts for the observed faces per block.");
        naturalClusters =
                n("cfNaturalClusters", "Natural clusters per budding block", .0875, 0, 1, .0001);
        naturalClusters.tooltip(
                "Vanilla .35 placement chance x 1/4 equally likely growth stages = .0875 clusters"
                    + " per exposed budding block.");
        group("cfRendering", "ChunkFinder / Rendering", false);
        fill = b("cfFill", "Chunk fill", true);
        outline = b("cfOutline", "Chunk outline", true);
        tracers = b("cfTracers", "Tracer lines", false);
        beams = b("cfBeams", "Geode beams", false);
        labels = b("cfLabels", "Floating labels", true);
        distance = n("cfDistance", "Render distance (blocks)", 256, 32, 1024, 16);
        minY = n("cfMinY", "Column minimum Y", -64, -2032, 2031, 1);
        maxY = n("cfMaxY", "Column maximum Y", 48, -2032, 2031, 1);
        fillAlpha = n("cfFillAlpha", "Fill alpha multiplier", .10, 0, 1, .01);
        outlineWidth = n("cfOutlineWidth", "Outline width (pixels)", 1.5, .5, 5, .1);
        fade = n("cfFade", "Fade duration (seconds)", .4, .1, 2, .05);
        group("cfColors", "ChunkFinder / Colors", false);
        lowColor = c("cfLowColor", "Low confidence", 0xcc70b8de);
        mediumColor = c("cfMediumColor", "Medium confidence", 0xddedbb67);
        highColor = c("cfHighColor", "High confidence", 0xeef17f98);
        outlineColor = c("cfOutlineColor", "Soft outline", 0xccd2e8f5);
        labelColor = c("cfLabelColor", "Label", 0xffedf6ff);
        singleMode = b("cfSingleMode", "Single color mode", false);
        singleColor = c("cfSingleColor", "Single detection color", 0xcc80ceb2);
        pulse = b("cfPulse", "Animated gradient / pulse", false);
        pulseA = c("cfPulseA", "Pulse color A", 0xcc80ceb2);
        pulseB = c("cfPulseB", "Pulse color B", 0xccb7a0e8);
        group("cfLog", "ChunkFinder / Log and notifications", false);
        chat = b("cfChat", "Chat on new detection", true);
        toast = b("cfToast", "Animated detection toast", true);
        debug = b("cfDebug", "Debug per-geode statistics", false);
        add(
                new ActionSetting(
                        "cfOpenLog",
                        "Open shared Basefinding log",
                        () ->
                                Minecraft.getInstance()
                                        .setScreen(
                                                new BasefindingLogScreen(
                                                        this, Minecraft.getInstance().screen))));
        add(new ActionSetting("cfExport", "Export detections", this::export));
        add(new ActionSetting("cfClearAll", "Clear all detections", this::clearDetections));
        add(
                new ActionSetting(
                        "cfReset",
                        "Reset ChunkFinder defaults",
                        () ->
                                settings().stream()
                                        .filter(s -> !(s instanceof ActionSetting))
                                        .forEach(Setting::reset)));
    }

    private BooleanSetting b(String key, String label, boolean value) {
        return add(new BooleanSetting(key, label, value));
    }

    private NumberSetting n(
            String key, String label, double value, double min, double max, double step) {
        return add(new NumberSetting(key, label, value, min, max, step));
    }

    private ColorSetting c(String key, String label, int color) {
        return add(new ColorSetting(key, label, color));
    }

    public boolean active() {
        return enabled()
                && finder.get()
                && world != null
                && world.dimension().equals(Level.OVERWORLD)
                && loading == null;
    }

    public ClientLevel world() {
        return world;
    }

    public Collection<Detection> detections() {
        return Collections.unmodifiableCollection(detections.values());
    }

    public boolean ready() {
        return world != null
                && server != null
                && world.dimension().equals(Level.OVERWORLD)
                && loading == null;
    }

    public boolean excluded(int x, int z) {
        if (spawnRadius.get() <= 0) return false;
        // Exclude every chunk intersecting the circle, rather than testing just its center.
        double dx = Math.max(0, Math.max(x * 16.0, -(x * 16.0 + 16))),
                dz = Math.max(0, Math.max(z * 16.0, -(z * 16.0 + 16)));
        return dx * dx + dz * dz < spawnRadius.get() * spawnRadius.get();
    }

    public boolean display(Detection d) {
        return active()
                && !d.checked()
                && !d.falsePositive()
                && !excluded(d.chunkX(), d.chunkZ())
                && d.score() >= threshold.get()
                && GeodeScoring.confidenceRank(GeodeScoring.confidence(d.score()))
                        >= GeodeScoring.confidenceRank(minimumConfidence.get());
    }

    public GeodeScoring.Weights weights() {
        return new GeodeScoring.Weights(
                clusterWeight.get(),
                fillWeight.get(),
                anomalyWeight.get(),
                manMade.get() ? madeWeight.get() : 0,
                neighboring.get() ? neighborWeight.get() : 0,
                naturalFill.get(),
                naturalClusters.get());
    }

    @Override
    public void initialize() {
        log =
                new DetectionLog(
                        FabricLoader.getInstance().getConfigDir().resolve("scrimmy/basefinding"));
        scanner = new ChunkFinderScanner(this);
        scanner.initialize();
        renderer = new ChunkFinderRenderer(this);
        renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientChunkEvents.CHUNK_LOAD.register(
                (level, chunk) -> {
                    if (chunk != null) scanner.load(level, chunk);
                });
        ClientChunkEvents.CHUNK_UNLOAD.register(scanner::unload);
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc, level) -> session(level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> session(null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                mc -> {
                    session(null);
                    scanner.close();
                    renderer.close();
                    log.close();
                });
        session(Minecraft.getInstance().level);
    }

    private void session(ClientLevel next) {
        if (next == world) return;
        if (log != null) log.flush();
        if (scanner != null) scanner.reset(next);
        if (renderer != null) renderer.clear();
        world = next;
        detections.clear();
        live.clear();
        observed.clear();
        scoring.clear();
        loading = null;
        server = dimension = null;
        if (next == null || !next.dimension().equals(Level.OVERWORLD) || log == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null)
            server =
                    "singleplayer:"
                            + mc.getSingleplayerServer()
                                    .getWorldPath(
                                            net.minecraft.world.level.storage.LevelResource.ROOT)
                                    .toAbsolutePath()
                                    .normalize();
        else if (mc.getCurrentServer() != null)
            server = "multiplayer:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        else {
            server = null;
            return;
        }
        dimension = next.dimension().identifier().toString();
        loading = log.load(server, dimension);
    }

    @Override
    protected void onEnable() {
        if (scanner != null) scanner.reset(world);
        rescore = true;
    }

    @Override
    protected void onDisable() {
        if (scanner != null) scanner.reset(world);
        live.clear();
        observed.clear();
        scoring.clear();
    }

    @Override
    protected void onSettingChanged(Setting<?> s) {
        if (s instanceof GroupSetting || s instanceof ActionSetting) return;
        rescore = true;
        if (scanner != null
                && (s == finder || s == manMade || s == manMadeRadius || s == spawnRadius)) {
            scanner.reset(world);
            live.clear();
            observed.clear();
        }
    }

    @Override
    public void onTick(Minecraft mc) {
        if (mc.level != world) session(mc.level);
        if (loading != null && loading.isDone()) {
            try {
                for (Detection d : loading.join()) detections.put(d.key(), d);
            } catch (Exception e) {
                error("Could not load detections", e);
            }
            loading = null;
            rescore = true;
            renderer.refresh();
        }
        if (!active()) return;
        ticks++;
        if (rescore) {
            rescore = false;
            scoring.addAll(detections.keySet());
            scoring.addAll(live.keySet());
        }
        long deadline = System.nanoTime() + (long) (scanBudget.get() * 250_000);
        boolean changed = false;
        while (!scoring.isEmpty() && System.nanoTime() < deadline) {
            Iterator<Long> next = scoring.iterator();
            long key = next.next();
            next.remove();
            changed |= recalculate(key);
        }
        if (changed) save();
        if (ticks % 10 == 0) renderer.refresh();
        scanner.tick(mc);
    }

    public void accept(int x, int z, List<GeodeAnalysis.Geode> geodes) {
        long key = Detection.key(x, z);
        if (geodes.isEmpty()) {
            live.remove(key);
            observed.remove(key);
        } else {
            live.put(key, geodes);
            observed.put(key, System.currentTimeMillis());
        }
        // Historical suspicious detections survive rescans with harvested/hidden buds.
        queueNeighbors(x, z);
    }

    public void unloaded(long key) {
        live.remove(key);
        observed.remove(key);
        queueNeighbors((int) key, (int) (key >>> 32));
    }

    private void queueNeighbors(int x, int z) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) scoring.add(Detection.key(x + dx, z + dz));
    }

    private boolean recalculate(long key) {
        GeodeScoring.Weights w = weights();
        Detection previous = detections.get(key);
        List<GeodeAnalysis.Geode> geodes = live.get(key);
        if (geodes == null) {
            if (previous == null) return false;
            geodes = previous.geodes();
        }
        int x = (int) key, z = (int) (key >>> 32);
        double score = 0;
        if (excluded(x, z)) return false;
        LongOpenHashSet own = new LongOpenHashSet(), neighbors = new LongOpenHashSet();
        for (var g : geodes) own.add(g.id());
        for (int oz = -1; oz <= 1; oz++)
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oz == 0) continue;
                long neighborKey = Detection.key(x + ox, z + oz);
                Detection historical = detections.get(neighborKey);
                if (historical != null && historical.falsePositive()) continue;
                List<GeodeAnalysis.Geode> others = live.get(neighborKey);
                if (others == null && historical != null) others = historical.geodes();
                if (others == null) continue;
                for (var g : others) {
                    if (!own.contains(g.id()) && GeodeScoring.score(g.stats(), w, 0).score() >= 60)
                        neighbors.add(g.id());
                }
            }
        for (var g : geodes) {
            var breakdown = GeodeScoring.score(g.stats(), w, neighbors.size());
            score = Math.max(score, breakdown.score());
            if (debug.get())
                LoggerFactory.getLogger("ScrimmyClient")
                        .info(
                                "ChunkFinder geode {},{},{} stats={} breakdown={}",
                                g.x(),
                                g.y(),
                                g.z(),
                                g.stats(),
                                breakdown);
        }
        if (previous == null
                && (score < threshold.get() || detections.size() >= DetectionLog.LIMIT))
            return false;
        long now =
                observed.getOrDefault(
                        key, previous == null ? System.currentTimeMillis() : previous.lastSeen());
        // Avoid rewriting timestamps/logs just because an unrelated visual setting changed.
        if (previous != null
                && previous.geodes().equals(geodes)
                && Math.abs(previous.score() - score) < .001
                && now <= previous.lastSeen()) return false;
        Detection next =
                new Detection(
                        x,
                        z,
                        geodes,
                        score,
                        previous == null ? now : previous.firstSeen(),
                        now,
                        previous == null ? "" : previous.notes(),
                        previous != null && previous.checked(),
                        previous != null && previous.falsePositive());
        detections.put(key, next);
        if (previous == null) notify(next);
        return true;
    }

    private void notify(Detection d) {
        String message =
                "ChunkFinder ["
                        + d.chunkX()
                        + ", "
                        + d.chunkZ()
                        + "] "
                        + Math.round(d.score())
                        + "/100 · "
                        + GeodeScoring.confidence(d.score())
                        + " · "
                        + d.coordinates();
        if (chat.get()) message(message);
        if (toast.get() && renderer != null) renderer.toast(message);
    }

    public void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal("[Basefinding] " + text), false);
    }

    private void save() {
        if (log != null && server != null && ready()) {
            log.save(server, dimension, detections.values());
            renderer.refresh();
        }
    }

    public void remove(long key) {
        detections.remove(key);
        live.remove(key);
        observed.remove(key);
        scoring.remove(key);
        save();
    }

    public void annotate(long key, String notes, boolean checked, boolean falsePositive) {
        Detection d = detections.get(key);
        if (d != null) {
            detections.put(key, d.annotation(notes, checked, falsePositive));
            save();
        }
    }

    public void clearDetections() {
        if (!ready()) return;
        detections.clear();
        live.clear();
        observed.clear();
        scoring.clear();
        save();
    }

    public void export() {
        if (log == null || server == null || !ready()) return;
        log.export(server, dimension, detections.values())
                .whenComplete(
                        (path, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (error != null) {
                                                        error("Export failed", error);
                                                        message(
                                                                "Export failed; see the client"
                                                                    + " log.");
                                                    } else message("Exported to " + path);
                                                }));
    }

    public void error(String text, Throwable e) {
        LoggerFactory.getLogger("ScrimmyClient").error(text, e);
    }
}
