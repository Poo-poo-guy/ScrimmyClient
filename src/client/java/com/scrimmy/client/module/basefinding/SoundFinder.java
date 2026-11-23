package com.scrimmy.client.module.basefinding;

import com.google.gson.JsonObject;
import com.scrimmy.client.gui.SoundIgnoreScreen;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.SoundFinderRenderer;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.sound.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import java.util.Arrays;

/** Server-sent sound evidence only; never registers game content or plays synthetic sounds. */
public final class SoundFinder extends Module implements SoundFinderRenderer.Provider {
    public final NumberSetting duration, fadeTime, maxSources, chunkHeight, angle, mediumThreshold, redThreshold,
            weakWeight, mechanicalWeight, unusualWeight, criticalWeight, repeatWeight, clusterWeight,
            sustainedWeight, repeatWindow, sustainedAfter, maxDistance, distanceFade,
            animationIn, animationOut, lingerTime, hysteresisMargin;
    public final BooleanSetting throughWalls, softFill, chunkHighlight, entitySounds, logDetections, redFlagPulse;
    public final ModeSetting nametagMode, minimumSeverity, animationStyle;
    public final ColorSetting weakColor, mediumColor, redColor;
    public final SoundTracker tracker = new SoundTracker();
    private static final String DEFAULT_IGNORE = "ambient., weather., block.water., block.lava., entity.player.swim, "
            + "entity.player.splash, entity.fish., entity.cod., entity.salmon., entity.tropical_fish., entity.puffer_fish., "
            + "block.bubble_column., block.fire.ambient, block.campfire.crackle, block.pointed_dripstone.drip";
    private String ignoreList = DEFAULT_IGNORE;
    private String[] ignorePrefixes = parsePrefixes(DEFAULT_IGNORE);
    private ClientLevel world;
    private SoundFinderRenderer renderer;
    private SoundDetectionLog log;

    public SoundFinder() {
        super("SoundFinder", "Locate server sound sources and repeated activity through terrain", Category.BASEFINDING);
        group("tracking", "Tracking", true);
        duration = number("duration", "Highlight duration", 30, 1, 600, 1);
        fadeTime = number("fadeTime", "Fade time", 5, 0, 30, .5);
        maxSources = number("maxSources", "Max sources", 256, 16, 1024, 16);
        entitySounds = add(new BooleanSetting("entitySounds", "Entity sounds", false));
        var editIgnore = add(new ActionSetting("ignoreList", "Edit ignore list", () -> {
            Minecraft mc = Minecraft.getInstance(); mc.setScreen(new SoundIgnoreScreen(this, mc.screen));
        }));
        logDetections = add(new BooleanSetting("logDetections", "Log detections", false));
        group("rendering", "Rendering", true);
        throughWalls = add(new BooleanSetting("throughWalls", "Through walls", true));
        softFill = add(new BooleanSetting("softFill", "Soft fill", false));
        chunkHighlight = add(new BooleanSetting("chunkHighlight", "Chunk highlight", false));
        chunkHeight = number("chunkHeight", "Chunk highlight height", 64, -64, 320, 1);
        maxDistance = number("maxDistance", "Max render distance", 256, 16, 1024, 16);
        distanceFade = number("distanceFade", "Distance fade (blocks)", 48, 0, 256, 4);
        minimumSeverity = add(new ModeSetting("minimumSeverity", "Minimum severity", "Weak", "Weak", "Medium", "Red flag"));
        nametagMode = add(new ModeSetting("nametagMode", "Nametag mode", "Look-at only",
                "Look-at only", "Look-at plus always show Red flags", "Off"));
        angle = number("angle", "Look-at angle", 4, .5, 20, .5);
        group("tagAnimation", "Nametag animation", false);
        animationStyle = add(new ModeSetting("animationStyle", "Animation style", "Pop", "Fade", "Pop", "Slide", "Glass expand"));
        animationIn = number("animationIn", "Animation in duration (ms)", 200, 0, 2000, 10);
        animationOut = number("animationOut", "Animation out duration (ms)", 250, 0, 2000, 10);
        lingerTime = number("lingerTime", "Linger time (ms)", 150, 0, 1000, 10);
        hysteresisMargin = number("hysteresisMargin", "Hysteresis margin (degrees)", 2, 0, 10, .25);
        redFlagPulse = add(new BooleanSetting("redFlagPulse", "Red flag pulse", true));
        group("severity", "Severity", false);
        mediumThreshold = number("mediumThreshold", "Medium threshold", 4, 1, 100, .5);
        redThreshold = number("redThreshold", "Red flag threshold", 12, 1, 200, .5);
        weakWeight = number("weakWeight", "One-off weight", 1, 0, 50, .5);
        mechanicalWeight = number("mechanicalWeight", "Piston / dispenser weight", 2, 0, 50, .5);
        unusualWeight = number("unusualWeight", "Unusual block weight", 4, 0, 50, .5);
        criticalWeight = number("criticalWeight", "Critical sound weight", 12, 0, 100, .5);
        repeatWeight = number("repeatWeight", "Repeat weight", 1, 0, 20, .5);
        repeatWindow = number("repeatWindow", "Repeat window (seconds)", 10, 1, 120, 1);
        clusterWeight = number("clusterWeight", "Per nearby source weight", 1.5, 0, 20, .5);
        sustainedWeight = number("sustainedWeight", "Sustained activity weight", 4, 0, 50, .5);
        sustainedAfter = number("sustainedAfter", "Sustained after (seconds)", 120, 60, 600, 10);
        group("colors", "Colors", false);
        weakColor = add(new ColorSetting("weakColor", "Weak color", 0xff819ab7));
        mediumColor = add(new ColorSetting("mediumColor", "Medium color", 0xffe4b878));
        redColor = add(new ColorSetting("redColor", "Red flag color", 0xffed7587));
        duration.tooltip("Seconds since the last packet before a source expires.");
        fadeTime.tooltip("Smoothly fade during the final seconds of the highlight duration.");
        entitySounds.tooltip("Mark entity sound packets separately, without block confirmation or chunk scoring.");
        logDetections.tooltip("Save new sources and severity increases to config/scrimmy/soundfinder/detections.jsonl.");
        distanceFade.tooltip("Fade over this many blocks before the maximum render distance; zero disables distance fading.");
        redThreshold.tooltip("Red flag cutoff. If below Medium, the Medium threshold is used.");
        editIgnore.tooltip("Edit comma-separated sound ID prefixes to ignore; namespace prefixes are supported.");
        maxSources.tooltip("Bound all block and entity markers; evict the first-heard source when full.");
        throughWalls.tooltip("Draw world outlines and fills without terrain depth testing.");
        softFill.tooltip("Add a faint translucent fill inside source block outlines.");
        chunkHighlight.tooltip("Outline chunks with activity, colored by their aggregate score.");
        chunkHeight.tooltip("World Y coordinate of the flat chunk outline.");
        maxDistance.tooltip("Skip world markers and nametags beyond this many blocks from the camera.");
        minimumSeverity.tooltip("Hide evidence below this severity without deleting its history.");
        nametagMode.tooltip("Show the closest source in the look cone, optionally also visible Red flags.");
        angle.tooltip("Cone half-angle in degrees; works through terrain and uses the camera direction.");
        animationStyle.tooltip("Fade changes opacity; Pop adds a soft overshoot; Slide moves the tag; Glass expand opens the pill in two axes.");
        animationIn.tooltip("Entrance duration in milliseconds, independent of frame rate; zero appears immediately.");
        animationOut.tooltip("Exit duration in milliseconds; outgoing tags remain until their progress reaches zero.");
        lingerTime.tooltip("Wait this many milliseconds outside the outer look cone before fading out; switching targets skips this delay.");
        hysteresisMargin.tooltip("Keep the current target until it exceeds the look-at angle plus this margin.");
        redFlagPulse.tooltip("Slowly pulse the border glow of visible Red flag nametags.");
        mediumThreshold.tooltip("Minimum score for the Medium rating.");
        weakWeight.tooltip("Base score for doors, buttons, ordinary containers and other one-off sounds.");
        mechanicalWeight.tooltip("Base score for pistons, dispensers and droppers.");
        unusualWeight.tooltip("Base score for anvils, enchanting tables, ender chests and shulkers.");
        criticalWeight.tooltip("Base score for explosions, portals, beacons, bells, withers and dragons.");
        repeatWeight.tooltip("Score per additional hit in the repeat window; saturates at 128 recent hits.");
        repeatWindow.tooltip("Count repeated packets within this rolling number of seconds.");
        clusterWeight.tooltip("Score per other distinct block source in the same chunk; entities are excluded.");
        sustainedWeight.tooltip("Bonus once sustained activity qualifies, then again per additional minute.");
        sustainedAfter.tooltip("Minimum seconds of uninterrupted source history before sustained activity qualifies.");
        weakColor.tooltip("Outline, fill and nametag accent for Weak sources.");
        mediumColor.tooltip("Outline, fill and nametag accent for Medium sources.");
        redColor.tooltip("Outline, fill and nametag accent for Red flag sources.");
        for (Setting<?> setting : settings()) if (setting instanceof GroupSetting)
            setting.tooltip("Expand or collapse the " + setting.label().toLowerCase(java.util.Locale.ROOT) + " controls.");
    }

    @Override public java.util.Collection<SoundTracker.Source> blocks() { return tracker.blocks(); }
    @Override public java.util.Collection<SoundTracker.Source> entities() { return tracker.entities(); }
    @Override public java.util.Collection<SoundTracker.Chunk> chunks() { return tracker.chunks(); }
    @Override public NumberSetting maxDistance() { return maxDistance; }
    @Override public ModeSetting nametagMode() { return nametagMode; }
    @Override public NumberSetting angle() { return angle; }
    @Override public NumberSetting hysteresisMargin() { return hysteresisMargin; }
    @Override public BooleanSetting softFill() { return softFill; }
    @Override public BooleanSetting chunkHighlight() { return chunkHighlight; }
    @Override public NumberSetting chunkHeight() { return chunkHeight; }
    @Override public NumberSetting animationIn() { return animationIn; }
    @Override public NumberSetting animationOut() { return animationOut; }
    @Override public NumberSetting lingerTime() { return lingerTime; }
    @Override public NumberSetting distanceFade() { return distanceFade; }
    @Override public ModeSetting animationStyle() { return animationStyle; }
    @Override public BooleanSetting redFlagPulse() { return redFlagPulse; }
    @Override public BooleanSetting throughWalls() { return throughWalls; }
    private NumberSetting number(String key, String label, double value, double min, double max, double step) {
        return add(new NumberSetting(key, label, value, min, max, step));
    }
    public ClientLevel world() { return world; }
    public String ignoreList() { return ignoreList; }
    public static String[] parsePrefixes(String text) {
        return Arrays.stream(text.split("[,;\\n]")).map(String::strip).filter(s -> !s.isEmpty()).limit(128).toArray(String[]::new);
    }
    public void ignoreList(String text) {
        ignorePrefixes = parsePrefixes(text.length() > 4096 ? text.substring(0, 4096) : text);
        ignoreList = String.join(", ", ignorePrefixes); changed();
    }
    @Override public JsonObject extraConfig() { JsonObject json = new JsonObject(); json.addProperty("ignorePrefixes", ignoreList); return json; }
    @Override public void extraConfig(JsonObject json) {
        if (json.has("ignorePrefixes") && json.get("ignorePrefixes").isJsonPrimitive()) {
            try { ignoreList(json.get("ignorePrefixes").getAsString()); } catch (RuntimeException ignored) { }
        }
    }
    public int severity(double score) { return SoundTracker.severity(score, mediumThreshold.get(), redThreshold.get()); }
    public int minimum() { return minimumSeverity.get().equals("Red flag") ? 2 : minimumSeverity.get().equals("Medium") ? 1 : 0; }
    public int color(int severity) { return severity == 2 ? redColor.get() : severity == 1 ? mediumColor.get() : weakColor.get(); }
    public static String label(int severity) { return severity == 2 ? "Red flag" : severity == 1 ? "Medium" : "Weak"; }
    @Override public void initialize() {
        renderer = new SoundFinderRenderer(this); renderer.initialize(); log = new SoundDetectionLog();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc, level) -> session(level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> session(null));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (world != mc.level) session(mc.level); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { session(null); renderer.close(); log.close(); });
        session(Minecraft.getInstance().level);
    }
    private void session(ClientLevel next) {
        if (next == world) return;
        world = next; tracker.clear(); if (renderer != null) renderer.clear(); if (log != null) log.clear();
    }
    @Override protected void onDisable() { tracker.clear(); if (renderer != null) renderer.clear(); if (log != null) log.clear(); }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (setting == entitySounds && !entitySounds.get()) tracker.clearEntities();
    }
    public void sound(ClientboundSoundPacket packet) {
        var sound = SoundCapture.read(packet);
        hear(sound.pos(), sound.id(), sound.category(), false);
    }
    public void entitySound(ClientboundSoundEntityPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || !entitySounds.get() || mc.level == null) return;
        var entity = mc.level.getEntity(packet.getId());
        if (entity != null) {
            var source = hear(entity.blockPosition(), packet.getSound().value().location().toString(), packet.getSource().getName(), true);
            if (source != null) { source.x = entity.getX(); source.y = entity.getY(); source.z = entity.getZ(); }
        }
    }
    public void levelEvent(ClientboundLevelEventPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.level == null) return;
        String sound = SoundCapture.levelSound(mc.level, packet);
        if (sound != null) hear(packet.getPos(), sound, SoundLevelEvents.category(packet.getType()), false);
    }
    private SoundTracker.Source hear(BlockPos pos, String sound, String category, boolean entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.level == null || SoundTracker.ignored(sound, ignorePrefixes)) return null;
        if (mc.level != world) session(mc.level);
        long now = System.currentTimeMillis();
        SoundTracker.Source source = tracker.hear(pos, sound, category, entity, now, maxSources.get().intValue());
        confirm(source);
        return source;
    }
    private void confirm(SoundTracker.Source s) {
        SoundCapture.confirm(world, s);
    }
    @Override public void onTick(Minecraft mc) {
        if (mc.level != world) session(mc.level);
        if (world == null || mc.player == null || mc.isPaused()) return;
        long now = System.currentTimeMillis();
        tracker.tick(now, (long)(duration.get() * 1000), (long)(fadeTime.get() * 1000), maxSources.get().intValue(),
                new SoundTracker.Weights(weakWeight.get(), mechanicalWeight.get(), unusualWeight.get(), criticalWeight.get(),
                        repeatWeight.get(), clusterWeight.get(), sustainedWeight.get(), (long)(repeatWindow.get() * 1000),
                        (long)(sustainedAfter.get() * 1000)));
        for (var source : tracker.blocks()) { confirm(source); if (logDetections.get() && log != null) log.record(mc, source, severity(source.score)); }
        if (log != null) log.retain(tracker);
        renderer.refresh(mc, now);
    }
}
