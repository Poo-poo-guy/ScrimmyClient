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
import java.util.IdentityHashMap;
import java.util.Map;

public final class BlockESPModule extends Module {
    private static final String[] GROUPS = {"Storage", "Ores", "Ancient Debris", "Spawners", "Portals", "Beds", "Respawn Anchors"};
    private static final int[] DEFAULTS = {0xff55b88a, 0xff82bea0, 0xffbc8e76, 0xffbb859f, 0xff9586c4, 0xff7bb7ab, 0xffa18ac4};
    public final MultiSelectSetting groups;
    public final BlockListSetting custom;
    public final NumberSetting range;
    public final BooleanSetting throughWalls, tracers;
    public final ModeSetting mode;
    public static final int MAX_BLOCKS = 512;
    public static final double SCAN_SPEED = 1;
    public static final double THICKNESS = 1.5;
    public static final double FILL_OPACITY = .12;
    public static final double HIDDEN_OPACITY = .32;
    public static final double FADE_TIME = .3;
    public static final double DISTANCE_FADE = .65;
    public static final double PULSE_STRENGTH = .12;
    public static final double PULSE_SPEED = .5;
    public static final boolean DIM_OCCLUDED = true;
    public static final boolean PULSE = false;
    public static final boolean HIDE_TARGET = false;
    public static final double TRACER_OPACITY = .45;
    public static final int MAX_TRACERS = 32;
    public static final double TRACER_WIDTH = 1;
    public final BlockEspCache cache = new BlockEspCache(this);
    private final BlockEspRenderer renderer = new BlockEspRenderer(this);
    private final Map<Block, Integer> colors = new IdentityHashMap<>();
    private boolean settingsDirty = true;

    public BlockESPModule() {
        super("BlockESP", "Cached, smoothly fading block highlights and tracers", Category.RENDER);
        groups = add(new MultiSelectSetting("blockGroups", "Blocks",
                java.util.List.of("Storage", "Ancient Debris", "Spawners"), true, GROUPS));
        custom = add(new BlockListSetting("customBlocks", "Extra blocks", BlockESPModule::validBlock));
        range = n("range", "Range", 96, 16, 256, 8);
        mode = m("mode", "Style", "Outline + Fill", "Outline", "Fill", "Outline + Fill");
        throughWalls = b("throughWalls", "Through walls", true);
        tracers = b("tracersEnabled", "Tracers", false);
        groups.tooltip("Choose the kinds of blocks to highlight.");
        custom.tooltip("Add individual blocks to highlight.");
        range.tooltip("Look for blocks within this many blocks of you.");
        mode.tooltip("Draw outlines, filled boxes, or both.");
        throughWalls.tooltip("Show highlights behind solid blocks.");
        tracers.tooltip("Draw a line toward nearby highlighted blocks.");
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
            if ((group < 0 || !groups.get().contains(GROUPS[group])) && !custom.get().containsKey(key)) continue;
            int color = custom.get().getOrDefault(key, group >= 0 ? DEFAULTS[group] : ScrimmyClient.THEME.accent.get());
            next.put(block, color);
        }
        boolean changed = !next.keySet().equals(colors.keySet());
        colors.clear(); colors.putAll(next);
        if (changed) cache.rebuild();
        settingsDirty = false;
    }
    public boolean matches(Block block) { return colors.containsKey(block); }
    public int color(Block block, double seconds, double distance) {
        return colors.getOrDefault(block, ScrimmyClient.THEME.accent.get());
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
