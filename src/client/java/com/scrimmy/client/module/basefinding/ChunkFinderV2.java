package com.scrimmy.client.module.basefinding;

import com.google.gson.JsonObject;
import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.*;
import com.scrimmy.client.basefinding.HumanActivityAnalysis.*;
import com.scrimmy.client.gui.ChunkFinderV2Screen;
import com.scrimmy.client.gui.ChunkFinderV2KeyScreen;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.ChunkFinderV2Renderer;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.trail.TunnelAnalysis;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** Separate, opt-in human activity scoring. No network or common-side content. */
public final class ChunkFinderV2 extends Module {
    public final NumberSetting minimum, radius, topN, exposedWeight, tunnelWeight, leakWeight, growthWeight, surfaceWeight, neighbors;
    public final BooleanSetting grid, outlines, nametags;
    public final NumberSetting baseKey = new NumberSetting("baseKey", "Mark base key", GLFW.GLFW_KEY_B, -1, GLFW.GLFW_KEY_LAST, 1);
    public final NumberSetting notBaseKey = new NumberSetting("notBaseKey", "Mark not base key", GLFW.GLFW_KEY_N, -1, GLFW.GLFW_KEY_LAST, 1);
    public ChunkFinderScanner geodeScanner;
    public DrillTrailScanner trailScanner;
    public ActivityTerrainScanner terrainScanner;
    private ChunkFinderV2Renderer renderer;
    private V2LocalData files;
    private ClientLevel world;
    private String scope;
    private final Map<Long, Terrain> terrain = new HashMap<>();
    private final Map<Long, List<TunnelAnalysis.Sample>> tunnels = new HashMap<>();
    private final Map<Long, Double> growth = new HashMap<>();
    private final Map<Long, String> marks = new HashMap<>(), pendingMarks = new HashMap<>();
    private final Set<Long> changedDuringAnalysis = new HashSet<>(), invalidResults = new HashSet<>();
    private CompletableFuture<Map<Long,String>> loadingMarks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Scrimmy V2 evidence"); t.setDaemon(true); return t;
    });
    private Future<List<Ranked>> task;
    private List<Ranked> ranked = List.of(), shown = List.of();
    private boolean marksFailed;
    private boolean dirty = true, baseDown, notBaseDown;
    private int ticks;
    private long generation, submittedGeneration, completedGeneration = -1;
    public static final class RankedSetting extends Setting<Boolean> {
        public final ChunkFinderV2 module;
        public RankedSetting(ChunkFinderV2 module) { super("bestChunks", "Best chunks", false); this.module = module; }
    }

    public ChunkFinderV2() {
        super("ChunkFinder V2", "Rank loaded chunks by human activity to choose where to drill", Category.BASEFINDING);
        minimum = number("minimum", "Minimum score", 20, 0, 100, 1);
        radius = number("radius", "Scan radius (chunks)", 8, 1, 32, 1);
        topN = number("topN", "Top chunks", 8, 1, 32, 1);
        grid = add(new BooleanSetting("grid", "Chunk grid", true));
        outlines = add(new BooleanSetting("outlines", "World outlines", false));
        nametags = add(new BooleanSetting("nametags", "Nametags", true));
        add(new ActionSetting("ranked", "Ranked chunks / drill target", this::openRanked));
        add(new RankedSetting(this));
        group("weights", "Feature weights", false);
        exposedWeight = number("exposedWeight", "Exposed built blocks", 1, 0, 3, .1);
        tunnelWeight = number("tunnelWeight", "Shafts and tunnels", .8, 0, 3, .1);
        leakWeight = number("leakWeight", "Leaked fragments", 1, 0, 3, .1);
        growthWeight = number("growthWeight", "Amethyst growth", .6, 0, 3, .1);
        surfaceWeight = number("surfaceWeight", "Surface anomalies", .15, 0, 3, .05);
        neighbors = number("neighbors", "Neighbor share", .25, 0, .75, .05);
        group("tuning", "Marks and export", false);
        add(new ActionSetting("markBase", "Mark current chunk: base", () -> mark("base")));
        add(new ActionSetting("markNotBase", "Mark current chunk: not base", () -> mark("not base")));
        add(new ActionSetting("bindBase", "Set mark base key (default B)", () -> bind(baseKey)));
        add(new ActionSetting("bindNotBase", "Set not base key (default N)", () -> bind(notBaseKey)));
        add(new ActionSetting("export", "Export feature CSV", this::export));
        baseKey.onChange(this::changed); notBaseKey.onChange(this::changed);
        for (Setting<?> setting : settings()) setting.tooltip(switch (setting.key()) {
            case "minimum" -> "Hide lower scores in the grid, nametags and ranked list; CSV keeps all scored chunks.";
            case "radius" -> "Chunks around you to scan, limited to data already loaded by the client.";
            case "topN" -> "Maximum ranked chunks with world nametags.";
            case "neighbors" -> "Blend this share of the eight neighboring chunks into each score. Missing neighbors contribute zero.";
            case "surfaceWeight" -> "Weight for broad flat patches, straight terraces, narrow dirt/cobble pillars and cobble/obsidian casts.";
            case "leakWeight" -> "Weight for exposed connected fragments outside detected shafts' one-block wall ring.";
            case "export" -> "Write all current raw features, scores, fragment sizes and local labels under the game directory.";
            default -> setting.label()+". Local loaded-data evidence only; scores are not probabilities.";
        });
    }
    private NumberSetting number(String key, String name, double value, double min, double max, double step) {
        return add(new NumberSetting(key,name,Math.round((value-min)/step)*step+min,min,max,step));
    }
    public ClientLevel world() { return world; }
    public List<Ranked> ranked() { return ranked; }
    public List<Ranked> visible() { return shown; }
    public String markAt(long key) { return marks.getOrDefault(key, ""); }
    public boolean ready() { return scope != null && loadingMarks == null && !marksFailed; }
    public int loadedCount() { return terrainScanner == null ? 0 : terrainScanner.loadedCount(); }
    private Weights weights() { return new Weights(exposedWeight.get(),tunnelWeight.get(),leakWeight.get(),growthWeight.get(),surfaceWeight.get(),neighbors.get()); }

    @Override public void initialize() {
        files = new V2LocalData(FabricLoader.getInstance().getGameDir());
        terrainScanner = new ActivityTerrainScanner(this); terrainScanner.initialize();
        geodeScanner = new ChunkFinderScanner(new ChunkFinderScanner.Consumer() {
            public boolean active() { return enabled() && world != null && world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD); }
            public int radius() { return ChunkFinderV2.this.radius.get().intValue(); }
            public void accept(int x, int z, List<GeodeAnalysis.Geode> found) {
                long key = net.minecraft.world.level.ChunkPos.asLong(x,z);
                growth.put(key,found.stream().mapToDouble(g -> BaseLikelihood.growth(g.stats())).max().orElse(0)); evidenceChanged(key);
            }
            public void unloaded(long key) { growth.remove(key); evidenceChanged(key); }
            public void error(String message, Throwable error) { ChunkFinderV2.this.error(message,error); }
        }); geodeScanner.initialize();
        trailScanner = new DrillTrailScanner(new DrillTrailScanner.Consumer() {
            public boolean enabled() { return ChunkFinderV2.this.enabled(); }
            public double rangeBlocks() { return radius.get()*16; }
            public boolean smart() { return true; }
            public void accept(TunnelAnalysis.Chunk chunk) { acceptTunnels(chunk); }
            public void error(String message, Throwable error) { ChunkFinderV2.this.error(message,error); }
        }); trailScanner.initialize();
        renderer = new ChunkFinderV2Renderer(this); renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientChunkEvents.CHUNK_LOAD.register(this::load);
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            terrainScanner.unload(level,chunk); geodeScanner.unload(level,chunk); trailScanner.unload(level,chunk);
        });
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc,level) -> session(level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc) -> session(null));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != world) session(mc.level);
            if (loadingMarks != null && loadingMarks.isDone()) finishMarks();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            terrainScanner.close(); geodeScanner.close(); trailScanner.close(); renderer.close(); files.close(); worker.shutdownNow();
        });
        session(Minecraft.getInstance().level);
    }
    private void load(ClientLevel level, LevelChunk chunk) {
        if (chunk == null) return;
        terrainScanner.load(level,chunk); geodeScanner.load(level,chunk); trailScanner.load(level,chunk);
    }
    public void changed(ClientLevel level, int x, int z) {
        if (terrainScanner == null) return;
        terrainScanner.changed(level,x,z); geodeScanner.changed(level,x,z); trailScanner.changed(level,x,z);
    }
    private void session(ClientLevel next) {
        if (next == world) return;
        world = next; resetScans(); marks.clear(); pendingMarks.clear(); loadingMarks = null; scope = null; marksFailed = false;
        if (next == null || files == null) return;
        Minecraft mc = Minecraft.getInstance(); String server;
        if (mc.getSingleplayerServer() != null) server = "singleplayer:"+mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        else if (mc.getCurrentServer() != null) server = "multiplayer:"+mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        else return;
        scope = V2LocalData.scope(server,next.dimension().identifier().toString()); loadingMarks = files.load(scope);
    }
    private void resetScans() {
        if (task != null) task.cancel(true); task = null;
        changedDuringAnalysis.clear(); invalidResults.clear();
        terrain.clear(); tunnels.clear(); growth.clear(); ranked = shown = List.of(); completedGeneration = -1; evidenceChanged();
        if (terrainScanner != null) terrainScanner.reset(world);
        if (geodeScanner != null) geodeScanner.reset(world);
        if (trailScanner != null) trailScanner.reset(world);
        if (renderer != null) renderer.clear();
    }
    @Override protected void onEnable() { resetScans(); }
    @Override protected void onDisable() { resetScans(); }
    @Override protected void onSettingChanged(Setting<?> setting) { dirty = true; }
    private void evidenceChanged() { dirty = true; generation++; }
    private void evidenceChanged(long key) {
        evidenceChanged();
        if (task != null && changedDuringAnalysis.add(key)) {
            // A raw fragment reads its adjacent chunks; smoothing reads their neighbors too.
            int x = (int)key, z = (int)(key >>> 32);
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                invalidResults.add(com.scrimmy.client.trail.TrailMath.chunk(x+dx,z+dz));
        }
    }
    public void invalidateTerrain(long key) { if (terrain.remove(key) != null) evidenceChanged(key); }
    public void acceptTerrain(Terrain value) { terrain.put(value.key(),value); evidenceChanged(value.key()); }
    public void unloaded(long key) {
        terrain.remove(key); tunnels.remove(key); growth.remove(key);
        ranked = ranked.stream().filter(r -> r.key() != key).toList(); evidenceChanged(key);
    }
    private void acceptTunnels(TunnelAnalysis.Chunk chunk) {
        List<TunnelAnalysis.Sample> merged = new ArrayList<>();
        if (chunk.edges() != 0) for (var sample : tunnels.getOrDefault(chunk.key(),List.of()))
            if (!TunnelAnalysis.inEdges(sample.x(),sample.z(),chunk.edges())) merged.add(sample);
        merged.addAll(chunk.samples()); tunnels.put(chunk.key(),List.copyOf(merged)); evidenceChanged(chunk.key());
    }
    @Override public void onTick(Minecraft mc) {
        if (mc.level != world) session(mc.level);
        if (loadingMarks != null && loadingMarks.isDone()) finishMarks();
        if (world == null || mc.player == null || mc.isPaused()) return;
        boolean b = down(mc,baseKey), n = down(mc,notBaseKey);
        if (mc.screen == null) { if (b && !baseDown) mark("base"); if (n && !notBaseDown) mark("not base"); }
        baseDown = b; notBaseDown = n;
        terrainScanner.tick(mc); trailScanner.tick(mc); geodeScanner.tick(mc);
        if (task != null && task.isDone()) {
            try {
                // Keep unaffected chunks flowing even when an active farm/shaft keeps changing.
                ranked = task.get().stream().filter(r -> terrain.containsKey(r.key()) && !invalidResults.contains(r.key())).toList();
                completedGeneration = submittedGeneration == generation ? generation : -1;
                if (submittedGeneration != generation) dirty = true;
            }
            catch (CancellationException ignored) { }
            catch (Exception e) { error("V2 feature analysis failed",e); }
            task = null;
        }
        if (++ticks % 10 == 0 && dirty && task == null) {
            Map<Long,Terrain> terrainCopy = Map.copyOf(terrain);
            Map<Long,List<TunnelAnalysis.Sample>> tunnelCopy = Map.copyOf(tunnels);
            Map<Long,Double> growthCopy = Map.copyOf(growth); Weights weights = weights();
            List<Ranked> previous = ranked; boolean reuseFeatures = completedGeneration == generation;
            changedDuringAnalysis.clear(); invalidResults.clear();
            submittedGeneration = generation; dirty = false;
            task = worker.submit(() -> {
                Map<Long,Features> features;
                if (reuseFeatures) {
                    features = new HashMap<>(); for (Ranked row : previous) features.put(row.key(),row.features());
                } else features = HumanActivityAnalysis.features(terrainCopy,
                        tunnelCopy.values().stream().flatMap(Collection::stream).toList(),growthCopy);
                return HumanActivityAnalysis.rank(features,weights);
            });
        }
        if (ticks % 5 == 0) {
            ranked = ranked.stream().filter(r -> terrain.containsKey(r.key())).toList();
            shown = ranked.stream().filter(r -> r.score() >= minimum.get()).toList(); renderer.refresh();
        }
    }
    private boolean down(Minecraft mc, NumberSetting key) {
        int value = key.get().intValue();
        return value >= GLFW.GLFW_KEY_SPACE && mc.getWindow() != null && GLFW.glfwGetKey(mc.getWindow().handle(),value) == GLFW.GLFW_PRESS;
    }
    private void finishMarks() {
        try { marks.putAll(loadingMarks.join()); }
        catch (Exception e) { marksFailed = true; error("Could not load V2 marks",e); message("Could not load local marks; see log. Existing file preserved."); }
        loadingMarks = null; marks.putAll(pendingMarks);
        pendingMarks.clear();
    }
    public void mark(String label) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || scope == null || !Set.of("base","not base").contains(label)) return;
        if (marksFailed) { message("Local marks unavailable; existing file preserved. See log."); return; }
        long key = net.minecraft.world.level.ChunkPos.asLong(mc.player.blockPosition().getX()>>4,mc.player.blockPosition().getZ()>>4);
        marks.put(key,label);
        if (loadingMarks != null) { pendingMarks.put(key,label); report(files.updateMark(scope,key,label),false); }
        else persistMarks();
        message("Marked chunk "+(int)key+", "+(int)(key>>>32)+" as "+label);
    }
    private void persistMarks() { report(files.save(scope,marks),false); }
    public void export() {
        if (!ready()) { message("Join a world and wait for local marks to load before exporting."); return; }
        report(files.export(scope,ranked,marks),true);
    }
    private void report(CompletableFuture<java.nio.file.Path> operation, boolean success) {
        operation.whenComplete((path,error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) { error("V2 local file operation failed",error); message("Local file operation failed; see log."); }
            else if (success) message("Exported "+path);
        }));
    }
    public void sendToDrillTrail(Ranked chunk) {
        DrillTrail drill = ScrimmyClient.MODULES.find(DrillTrail.class);
        if (drill == null || world == null) return;
        drill.setDrillTarget(chunk.x(),chunk.z());
        message("DrillTrail target: chunk "+chunk.x()+", "+chunk.z()+" (block "+(chunk.x()*16+8)+", "+(chunk.z()*16+8)+")");
    }
    public void openRanked() { Minecraft mc = Minecraft.getInstance(); mc.setScreen(new ChunkFinderV2Screen(this,mc.screen)); }
    private void bind(NumberSetting key) { Minecraft mc = Minecraft.getInstance(); mc.setScreen(new ChunkFinderV2KeyScreen(key,mc.screen)); }
    public void message(String text) { var player = Minecraft.getInstance().player; if (player != null) player.displayClientMessage(ClientFonts.component("[ChunkFinder V2] "+text),false); }
    public void error(String text, Throwable error) { LoggerFactory.getLogger("ScrimmyClient").error(text,error); }
    @Override public JsonObject extraConfig() {
        JsonObject json = new JsonObject(); json.addProperty("baseKey",baseKey.get()); json.addProperty("notBaseKey",notBaseKey.get()); return json;
    }
    @Override public void extraConfig(JsonObject json) {
        for (NumberSetting key : List.of(baseKey,notBaseKey)) if (json.has(key.key()))
            try { key.set(json.get(key.key()).getAsDouble()); } catch (RuntimeException ignored) { }
    }
}
