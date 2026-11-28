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

public final class LogoutGhosts extends Module implements LogoutGhostTracker.Listener {
    public static final int DEFAULT_COLOR = 0xD9D8F8FF;
    private static final float CHIME_PITCH = 1.1f, CHIME_VOLUME = .2f;
    public final NumberSetting duration, maxDrawDistance;
    public final BooleanSetting outOfRange, notifyChat, sound, showCoordinates;

    public final LogoutGhostTracker tracker = new LogoutGhostTracker();
    private final LogoutGhostRenderer renderer = new LogoutGhostRenderer(this);
    private final LogoutGhostTracker.TabList tab = uuid -> {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getPlayerInfo(uuid) != null;
    };
    private ClientLevel world;
    private LocalPlayer localPlayer;

    public static final double MAX_GHOSTS = 12;
    public static final double FADE_DISTANCE = 128;
    public static final double HEAD_SIZE = 1;
    public static final double LABEL_SIZE = 1;
    public static final boolean NOTIFY_VANISH = false;
    public static final boolean SHOW_BODY = true;
    public static final boolean SHOW_BEAM = true;
    public static final boolean SHOW_HEAD = true;
    public static final boolean SHOW_LABEL = true;
    public static final int GHOST_COLOR = DEFAULT_COLOR;
    public LogoutGhosts() {
        super("LogoutGhosts", "Fading markers at the last positions of departed players", Category.RENDER);
        duration = n("duration", "Duration (s)", 120, 10, 1800, 10);
        outOfRange = b("outOfRange", "Out of range", false);
        outOfRange.tooltip("Also mark unloaded players who are still in tab. Often this is just render distance.");
        notifyChat = b("notifyChat", "Chat messages", true);
        sound = b("sound", "Sound", false);
        showCoordinates = b("showCoordinates", "Coordinates", false);
        duration.tooltip("Keep markers for this many seconds.");
        notifyChat.tooltip("Post a message when a player leaves.");
        sound.tooltip("Play a sound when a player leaves.");
        showCoordinates.tooltip("Include the player's last position on the marker.");
        maxDrawDistance = n("maxDrawDistance", "Range", 256, 16, 1024, 16);
        maxDrawDistance.tooltip("Show markers within this many blocks.");
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
        tracker.finishTick(tab, outOfRange.get(), NOTIFY_VANISH, duration.get(), (int) MAX_GHOSTS, this);
        renderer.tick(mc);
    }
    private void snapshot(Snapshot snapshot, AbstractClientPlayer player) {
        if (snapshot.name == null) snapshot.name = player.getName().getString();
        snapshot.skin = player.getSkin();
        snapshot.x = player.getX(); snapshot.y = player.getY(); snapshot.z = player.getZ(); snapshot.yaw = player.getYRot();
        snapshot.width = player.getBbWidth(); snapshot.height = player.getBbHeight(); snapshot.dimension = player.level().dimension();
        snapshot.dead = player.getHealth() <= 0 || player.isDeadOrDying();
    }
    @Override public void created(Ghost ghost) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (notifyChat.get()) mc.player.displayClientMessage(com.scrimmy.client.render.ClientFonts.component("[LogoutGhosts] " + ghost.name + " · " + ghost.status.label
            + " at " + Math.round(ghost.x) + ", " + Math.round(ghost.y) + ", " + Math.round(ghost.z) + " (" + ghost.dimension.identifier() + ")"), false);
        if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, CHIME_PITCH, CHIME_VOLUME));
    }
    @Override public void vanished(Snapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(com.scrimmy.client.render.ClientFonts.component("[LogoutGhosts] " + snapshot.name + " disappeared from tab but is still nearby."), false);
    }
}
