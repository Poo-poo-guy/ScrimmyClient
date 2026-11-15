package com.scrimmy.client.module.visual;

import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.AntiVanishEffect;
import com.scrimmy.client.setting.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AntiVanish extends Module {
    // Empty means everyone. To restrict warnings, use exact lowercase names, e.g. Set.of("admin", "moderator").
    private static final Set<String> STAFF_NAMES = Set.of();
    public final NumberSetting range, cooldown, headSize, spinTurns;
    public final BooleanSetting hiddenFromTab, sound, testEffect;
    private final Map<UUID, Integer> lastWarnings = new HashMap<>();
    private final AntiVanishEffect effect = new AntiVanishEffect();
    private int ticks;
    private boolean restoring;

    public AntiVanish() {
        super("AntiVanish", "Warns about nearby loaded spectators or players missing from tab", Category.VISUAL);
        group("detection", "Detection", true);
        range = n("range", "Range", 8, 1, 64, 1);
        cooldown = n("cooldown", "Cooldown (s)", 30, 5, 300, 5);
        hiddenFromTab = b("hiddenFromTab", "Hidden From Tab", false);
        hiddenFromTab.tooltip("Also warn about players missing from tab. Can false-trigger on NPCs.");
        group("warning", "Warning effect", true);
        sound = b("sound", "Sound", true);
        headSize = n("headSize", "Head size", AntiVanishEffect.DEFAULT_HEAD_SIZE, .25, 2, .05);
        spinTurns = n("spinTurns", "Spin turns", AntiVanishEffect.DEFAULT_SPIN_TURNS, .5, 4, .5);
        testEffect = b("testEffect", "Test Effect", false);
        testEffect.tooltip("Preview once with your own head while AntiVanish is enabled and you are in a world.");
    }
    private NumberSetting n(String key, String label, double value, double min, double max, double step) { return add(new NumberSetting(key, label, value, min, max, step)); }
    private BooleanSetting b(String key, String label, boolean value) { return add(new BooleanSetting(key, label, value)); }
    @Override public void initialize() {
        effect.initialize();
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (setting != testEffect || !testEffect.get()) return;
        testEffect.set(false);
        if (restoring || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) warn(mc.player);
    }
    @Override public void beginRestore() { restoring = true; }
    @Override public void endRestore() { testEffect.set(false); restoring = false; }
    @Override protected void onEnable() { clear(); }
    @Override protected void onDisable() { clear(); }
    private void clear() { lastWarnings.clear(); ticks = 0; effect.stop(); }
    @Override public void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        ticks++;
        double rangeSquared = range.get() * range.get();
        var connection = mc.getConnection();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player || mc.player.distanceToSqr(player) > rangeSquared) continue;
            boolean watching = player.isSpectator() || (hiddenFromTab.get() && connection != null && connection.getPlayerInfo(player.getUUID()) == null);
            if (!watching) continue;
            String name = player.getName().getString();
            if (!STAFF_NAMES.isEmpty() && !STAFF_NAMES.contains(name.toLowerCase(Locale.ROOT))) continue;
            Integer last = lastWarnings.get(player.getUUID());
            if (last != null && ticks - last < cooldown.get() * 20) continue;
            lastWarnings.put(player.getUUID(), ticks);
            warn(player);
        }
    }
    private void warn(AbstractClientPlayer player) {
        effect.warn(player.getName().getString(), player.getSkin(), sound.get(), headSize.get().floatValue(), spinTurns.get().floatValue());
    }
}
