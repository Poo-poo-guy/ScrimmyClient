package com.scrimmy.client.module.visual;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.esp.BlockEspCache;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.BlockEspRenderer;
import com.scrimmy.client.setting.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;

public final class BlockESPModule extends Module {
    private static final String[] GROUPS = {"Storage", "Ores", "Ancient Debris", "Spawners", "Portals", "Beds", "Respawn Anchors"};
    private static final int[] DEFAULTS = {0xff55b88a, 0xff82bea0, 0xffbc8e76, 0xffbb859f, 0xff9586c4, 0xff7bb7ab, 0xffa18ac4};
    public final BooleanSetting[] groups = new BooleanSetting[GROUPS.length];
    public final ColorSetting[] groupColors = new ColorSetting[GROUPS.length];
    public final BlockListSetting custom, blockColors;
    public final NumberSetting range, maxBlocks, scanSpeed, thickness, fillOpacity, hiddenOpacity, fadeTime, distanceFade, pulseStrength, pulseSpeed;
    public final BooleanSetting throughWalls, dimOccluded, pulse, hideTarget, tracers;
    public final NumberSetting tracerOpacity, maxTracers, tracerWidth, rainbowSpeed;
    public final ModeSetting mode, colorSource, colorEffect;
    public final ColorSetting singleColor;
    public final GradientSetting gradient;
    public final BlockEspCache cache = new BlockEspCache(this);
    private final BlockEspRenderer renderer = new BlockEspRenderer(this);
    private final Map<Block, Integer> colors = new IdentityHashMap<>();
    private boolean settingsDirty = true;

    public BlockESPModule() {
        super("BlockESP", "Cached, smoothly fading block highlights and tracers", Category.RENDER);
        group("blocks", "Blocks", true);
        for (int i = 0; i < groups.length; i++) groups[i] = b("group" + i, GROUPS[i], i == 0 || i == 2 || i == 3);
        custom = add(new BlockListSetting("customBlocks", "Custom blocks", BlockESPModule::validBlock));
        group("scanning", "Scanning", true);
        range = n("range", "Range (blocks)", 96, 16, 256, 8);
        maxBlocks = n("maxBlocks", "Max rendered blocks", 512, 16, BlockEspCache.MAX_RENDERED, 16);
        scanSpeed = n("scanSpeed", "Scan budget (ms/tick)", 1, .25, 3, .25);
        group("rendering", "Rendering", true);
        mode = m("mode", "Mode", "Outline + Fill", "Outline", "Fill", "Outline + Fill");
        throughWalls = b("throughWalls", "Through walls", true);
        dimOccluded = b("dimOccluded", "Dim occluded highlights", true);
        hiddenOpacity = n("hiddenOpacity", "Occluded opacity", .32, .05, 1, .01);
        thickness = n("thickness", "Outline width (pixels)", 1.5, .5, 5, .1);
        fillOpacity = n("fillOpacity", "Fill opacity", .12, 0, 1, .01);
        fadeTime = n("fadeTime", "Fade duration (seconds)", .3, .05, 2, .05);
        distanceFade = n("distanceFade", "Distance fade strength", .65, 0, 1, .01);
        pulse = b("pulse", "Gentle pulse", false);
        pulseStrength = n("pulseStrength", "Pulse strength", .12, 0, .5, .01);
        pulseSpeed = n("pulseSpeed", "Pulse speed (Hz)", .5, .1, 3, .1);
        hideTarget = b("hideTarget", "Hide looked-at block", false);
        group("tracers", "Tracers", false);
        tracers = b("tracersEnabled", "Tracers", false);
        tracerOpacity = n("tracerOpacity", "Tracer opacity", .45, 0, 1, .01);
        maxTracers = n("maxTracers", "Max tracers", 32, 1, 256, 1);
        tracerWidth = n("tracerWidth", "Tracer width (pixels)", 1, .5, 4, .1);
        group("colors", "Colors", false);
        colorSource = m("colorSource", "Color source", "Per block type", "Per block type", "Single color", "Client accent");
        singleColor = add(new ColorSetting("singleColor", "Single color", 0xff58bd8b));
        for (int i = 0; i < groupColors.length; i++) groupColors[i] = add(new ColorSetting("groupColor" + i, GROUPS[i] + " default", DEFAULTS[i]));
        blockColors = add(new BlockListSetting("blockColors", "Per-block color overrides", BlockESPModule::validBlock));
        colorEffect = m("colorEffect", "Color animation", "None", "None", "Rainbow", "Gradient");
        rainbowSpeed = n("rainbowSpeed", "Color animation speed", .12, 0, 1, .01);
        gradient = add(new GradientSetting("gradient", "Gradient colors", 0xff23664b, 0xff88d5aa));
    }
    private static boolean validBlock(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.BLOCK.containsKey(key) && !BuiltInRegistries.BLOCK.getValue(key).defaultBlockState().isAir();
    }
    private NumberSetting n(String key, String label, double value, double min, double max, double step) { return add(new NumberSetting(key, label, value, min, max, step)); }
    private BooleanSetting b(String key, String label, boolean value) { return add(new BooleanSetting(key, label, value)); }
    private ModeSetting m(String key, String label, String value, String... options) { return add(new ModeSetting(key, label, value, options)); }
    @Override protected void onSettingChanged(Setting<?> setting) { if (!(setting instanceof GroupSetting)) settingsDirty = true; }
    private static int groupOf(String path) {
        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel") || path.equals("ender_chest") || path.equals("shulker_box") || path.endsWith("_shulker_box")) return 0;
        if (path.endsWith("_ore")) return 1;
        if (path.equals("ancient_debris")) return 2;
        if (path.equals("spawner") || path.equals("trial_spawner")) return 3;
        if (path.equals("nether_portal") || path.equals("end_portal") || path.equals("end_portal_frame")) return 4;
        if (path.endsWith("_bed")) return 5;
        return path.equals("respawn_anchor") ? 6 : -1;
    }
    private void refreshSettings() {
        Map<Block, Integer> next = new IdentityHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            int group = id.getNamespace().equals("minecraft") ? groupOf(id.getPath()) : -1;
            String key = id.toString();
            if ((group < 0 || !groups[group].get()) && !custom.get().containsKey(key)) continue;
            int color = custom.get().getOrDefault(key, group >= 0 ? groupColors[group].get() : singleColor.get());
            next.put(block, blockColors.get().getOrDefault(key, color));
        }
        boolean changed = !next.keySet().equals(colors.keySet());
        colors.clear(); colors.putAll(next);
        if (changed) cache.rebuild();
        settingsDirty = false;
    }
    public boolean matches(Block block) { return colors.containsKey(block); }
    public int color(Block block, double seconds, double distance) {
        double phase = seconds * rainbowSpeed.get() + distance / Math.max(16, range.get());
        if (colorEffect.get().equals("Rainbow")) return Color.HSBtoRGB((float)(phase - Math.floor(phase)), .48f, .85f);
        if (colorEffect.get().equals("Gradient")) return gradient.sample(.5 + .5 * Math.sin(phase * Math.PI * 2));
        return switch (colorSource.get()) {
            case "Single color" -> singleColor.get();
            case "Client accent" -> ScrimmyClient.THEME.accent.get();
            default -> colors.getOrDefault(block, singleColor.get());
        };
    }
    @Override public void initialize() {
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> { if (enabled() && chunk != null) cache.load(level, chunk); });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> { if (enabled()) cache.unload(level, chunk); });
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    }
    @Override protected void onEnable() { settingsDirty = true; }
    @Override protected void onDisable() { clear(); }
    private void clear() { cache.clear(); renderer.close(); }
    @Override public void onTick(Minecraft client) {
        if (settingsDirty) refreshSettings();
        cache.tick(client);
    }
}
