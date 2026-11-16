package com.scrimmy.client.module.basefinding;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.DrillTrailScanner;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.DrillTrailRenderer;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.trail.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

public final class DrillTrail extends Module {
    public static final int GRAPH_INTERVAL_TICKS = 10, VISUAL_INTERVAL_TICKS = 5;
    public final NumberSetting range = add(new NumberSetting("range", "Range", 96, 32, 256, 16));
    public final BooleanSetting dimVisited = add(new BooleanSetting("dimVisited", "Dim Visited", true));
    public final BooleanSetting markLeads = add(new BooleanSetting("markLeads", "Mark Leads", true));
    public final BooleanSetting showDirection = add(new BooleanSetting("showDirection", "Show Direction", true));
    public final BooleanSetting hintHud = add(new BooleanSetting("hintHud", "Hint HUD", true));
    public final NumberSetting minimumLength = add(new NumberSetting("minimumLength", "Minimum Length",
            TunnelAnalysis.DEFAULT_MINIMUM_LENGTH, 6, 64, 1));
    public final TrailMemory memory = new TrailMemory();
    public DrillTrailScanner scanner;
    public DrillTrailRenderer renderer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Scrimmy DrillTrail paths"); thread.setDaemon(true); return thread;
    });
    private Future<TunnelNetwork.Network> task;
    private TunnelNetwork.Network network = TunnelNetwork.Network.EMPTY;
    private ClientLevel world;
    private boolean dirty = true;
    private int ticks;

    public DrillTrail() {
        super("DrillTrail", "Follow regular 3 by 3 tunnels toward unexplored leads", Category.BASEFINDING);
        range.tooltip("Scan and draw tunnels within this many blocks.");
        dimVisited.tooltip("Make parts you have already passed through less bright.");
        markLeads.tooltip("Mark tunnel ends, branches, and room openings.");
        showDirection.tooltip("Move a soft pulse along the trail toward unexplored parts.");
        hintHud.tooltip("Point to the nearest unexplored lead and show its distance.");
        minimumLength.tooltip("Ignore tunnels shorter than this many blocks.");
    }
    public ClientLevel world() { return world; }
    public TunnelNetwork.Network network() { return network; }
    /** Optional read-only integration; DrillTrail never enables or changes Chunk Finder. */
    public double baseScore(int x, int z) {
        Basefinding finder = ScrimmyClient.MODULES.find(Basefinding.class);
        return finder == null ? 0 : finder.scoreAtChunk(x, z);
    }
    @Override public void initialize() {
        scanner = new DrillTrailScanner(this); scanner.initialize();
        renderer = new DrillTrailRenderer(this); renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> { if (chunk != null) scanner.load(level, chunk); });
        ClientChunkEvents.CHUNK_UNLOAD.register(scanner::unload);
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc, level) -> session(level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> session(null));
        // Some disconnect paths clear Minecraft.level after the connection event. This guard
        // also runs while disabled, so session history cannot leak into a later server.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (mc.level != world) session(mc.level); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            session(null); scanner.close(); renderer.close(); worker.shutdownNow();
        });
        session(Minecraft.getInstance().level);
    }
    private void session(ClientLevel next) {
        if (next == world) return;
        if (task != null) task.cancel(true); task = null;
        world = next; memory.clear(); network = TunnelNetwork.Network.EMPTY; dirty = true; ticks = 0;
        if (scanner != null) scanner.reset(next);
        if (renderer != null) renderer.clear();
    }
    @Override protected void onEnable() { if (scanner != null) scanner.reset(world); dirty = true; }
    @Override protected void onDisable() {
        if (task != null) task.cancel(true); task = null;
        if (scanner != null) scanner.reset(world);
        if (renderer != null) renderer.refresh();
    }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (setting == minimumLength) dirty = true;
        if (renderer != null) renderer.refresh();
    }
    public void accept(TunnelAnalysis.Chunk chunk) {
        if (memory.put(chunk)) dirty = true;
    }
    @Override public void onTick(Minecraft mc) {
        if (mc.level != world) session(mc.level);
        if (world == null || mc.player == null || mc.isPaused()) return;
        ticks++;
        if (task != null && task.isDone()) {
            try { network = task.get(); }
            catch (CancellationException ignored) { }
            catch (Exception error) { error("Tunnel path analysis failed", error); }
            task = null; renderer.refresh();
        }
        scanner.tick(mc);
        if (ticks % 2 == 0) memory.visit(network, mc.player.getX(), mc.player.getY() + .9, mc.player.getZ());
        if (ticks % GRAPH_INTERVAL_TICKS == 0 && dirty && task == null) {
            var snapshot = memory.snapshot(); int minimum = minimumLength.get().intValue();
            task = worker.submit(() -> TunnelNetwork.build(snapshot, minimum)); dirty = false;
        }
        if (ticks % VISUAL_INTERVAL_TICKS == 0) renderer.refresh();
    }
    public void error(String message, Throwable error) { LoggerFactory.getLogger("ScrimmyClient").error(message, error); }
}
