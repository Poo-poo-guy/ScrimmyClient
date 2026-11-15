package com.scrimmy.client.module.visual;

import com.scrimmy.client.ghost.LogoutGhostTracker;
import com.scrimmy.client.ghost.LogoutGhostTracker.Ghost;
import com.scrimmy.client.ghost.LogoutGhostTracker.Snapshot;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.LogoutGhostRenderer;
import com.scrimmy.client.setting.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import java.util.UUID;

public final class LogoutGhosts extends Module implements LogoutGhostTracker.Listener {
    public static final int DEFAULT_COLOR = 0xD9D8F8FF;
    private static final double TEST_DISTANCE = 6;
    private static final float CHIME_PITCH = 1.1f, CHIME_VOLUME = .2f;
    public final NumberSetting duration, maxGhosts, maxDrawDistance, fadeDistance, headSize, labelSize;
    public final BooleanSetting outOfRange, notifyChat, notifyVanish, sound, showBody, showBeam, showHead, showLabel, showCoordinates, testGhost;
    public final ColorSetting ghostColor;
    public final LogoutGhostTracker tracker = new LogoutGhostTracker();
    private final LogoutGhostRenderer renderer = new LogoutGhostRenderer(this);
    private final LogoutGhostTracker.TabList tab = uuid -> {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getPlayerInfo(uuid) != null;
    };
    private ClientLevel world;
    private LocalPlayer localPlayer;
    private boolean restoring;

    public LogoutGhosts() {
        super("LogoutGhosts", "Fading markers at the last positions of departed players", Category.RENDER);
        group("tracking", "Tracking", true);
        duration = n("duration", "Duration (s)", 120, 10, 1800, 10);
        maxGhosts = n("maxGhosts", "Max Ghosts", 12, 1, LogoutGhostTracker.CAPACITY, 1);
        outOfRange = b("outOfRange", "Out Of Range Ghosts", false);
        outOfRange.tooltip("Also mark unloaded players who are still in tab. Often this is just render distance.");
        group("notifications", "Notifications", false);
        notifyChat = b("notifyChat", "Notify Chat", true);
        notifyVanish = b("notifyVanish", "Notify Vanish", false);
        sound = b("sound", "Sound", false);
        group("appearance", "Appearance", true);
        showBody = b("showBody", "Show Body", true);
        showBeam = b("showBeam", "Show Beam", true);
        showHead = b("showHead", "Show Head", true);
        showLabel = b("showLabel", "Show Label", true);
        showCoordinates = b("showCoordinates", "Show Coordinates", false);
        maxDrawDistance = n("maxDrawDistance", "Max Draw Distance", 256, 16, 1024, 16);
        fadeDistance = n("fadeDistance", "Fade Distance", 128, 16, 1024, 16);
        ghostColor = add(new ColorSetting("ghostColor", "Ghost Color", DEFAULT_COLOR));
        headSize = n("headSize", "Head size", 1, .5, 2, .05);
        labelSize = n("labelSize", "Label size", 1, .5, 2, .05);
        testGhost = b("testGhost", "Test Ghost", false);
        testGhost.tooltip("Create one preview six blocks ahead while enabled in a world.");
    }
    private NumberSetting n(String key, String label, double value, double min, double max, double step) { return add(new NumberSetting(key, label, value, min, max, step)); }
    private BooleanSetting b(String key, String label, boolean value) { return add(new BooleanSetting(key, label, value)); }
    @Override public void initialize() {
        renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { clear(); renderer.close(); });
    }
    @Override protected void onEnable() { clear(); }
    @Override protected void onDisable() { clear(); renderer.close(); }
    public ClientLevel world() { return world; }
    public boolean inWorld(Minecraft mc) { return mc.player != null && mc.level != null && mc.level == world && mc.player == localPlayer; }
    private void clear() { tracker.clear(); renderer.clear(); world = null; localPlayer = null; }
    @Override public void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        // A respawn replaces LocalPlayer even when the ClientLevel remains the same.
        if (world != mc.level || localPlayer != mc.player) { clear(); world = mc.level; localPlayer = mc.player; }
        if (mc.getConnection() == null) return;
        tracker.beginTick(System.nanoTime());
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;
            snapshot(tracker.observe(player.getUUID()), player);
        }
        tracker.finishTick(tab, outOfRange.get(), notifyVanish.get(), duration.get(), maxGhosts.get().intValue(), this);
        renderer.tick(mc);
    }
    private void snapshot(Snapshot snapshot, AbstractClientPlayer player) {
        if (snapshot.name == null) snapshot.name = player.getName().getString();
        snapshot.skin = player.getSkin();
        snapshot.x = player.getX(); snapshot.y = player.getY(); snapshot.z = player.getZ(); snapshot.yaw = player.getYRot();
        snapshot.width = player.getBbWidth(); snapshot.height = player.getBbHeight(); snapshot.dimension = player.level().dimension();
        snapshot.dead = player.getHealth() <= 0 || player.isDeadOrDying();
    }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (setting != testGhost || !testGhost.get()) return;
        testGhost.set(false);
        if (restoring || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (world != mc.level || localPlayer != mc.player) { clear(); world = mc.level; localPlayer = mc.player; }
        Snapshot snapshot = new Snapshot(); snapshot(snapshot, mc.player);
        // A separate preview UUID prevents our own tab entry from removing the test marker.
        snapshot.uuid = UUID.randomUUID();
        var look = mc.player.getLookAngle();
        snapshot.x += look.x * TEST_DISTANCE; snapshot.y += look.y * TEST_DISTANCE; snapshot.z += look.z * TEST_DISTANCE;
        tracker.test(snapshot, System.nanoTime(), maxGhosts.get().intValue(), this);
        renderer.tick(mc);
    }
    @Override public void beginRestore() { restoring = true; }
    @Override public void endRestore() { testGhost.set(false); restoring = false; }
    @Override public void created(Ghost ghost) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (notifyChat.get()) mc.player.displayClientMessage(Component.literal("[LogoutGhosts] " + ghost.name + " · " + ghost.status.label
            + " at " + Math.round(ghost.x) + ", " + Math.round(ghost.y) + ", " + Math.round(ghost.z) + " (" + ghost.dimension.identifier() + ")"), false);
        if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, CHIME_PITCH, CHIME_VOLUME));
    }
    @Override public void vanished(Snapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[LogoutGhosts] " + snapshot.name + " disappeared from tab but is still nearby."), false);
    }
}
