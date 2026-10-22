package com.scrimmy.client.module.visual;

import com.google.gson.JsonObject;
import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.particle.ParticlePool;
import com.scrimmy.client.render.HitParticleRenderer;
import com.scrimmy.client.setting.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HitParticles extends Module {
    public static final String[] SHAPES = {"Star", "Sparkle", "Circle", "Heart", "Diamond", "Cross", "Snowflake", "Triangle", "Ring"};
    private static final String[] EASINGS = {"Linear", "Ease-out", "Ease-in-out", "Back", "Elastic"};
    public final NumberSetting count, countRandom, lifeMin, lifeMax, cap, spawnRadius, distance;
    public final ModeSetting spawn, trigger, shape, points, colorMode, motion, twinkleMode, preset, userSlot;
    public final MultiSelectSetting targets, pool;
    public final BooleanSetting throughWalls, outline, colorLifetime, shapeColors, randomRotation, randomSpin, billboard, additive, twinkle, trail, sound;
    public final NumberSetting inner, size, sizeRandom, endSize, outlineWidth, rainbowSpeed, saturation, alphaStart, alphaEnd;
    public final ColorSetting startColor, endColor, outlineColor;
    public final GradientSetting gradient, palette;
    public final ColorSetting[] overrides = new ColorSetting[SHAPES.length];
    public final NumberSetting speedMin, speedMax, spread, drag, gravity, spin, glow, glowSize, twinkleSpeed, twinkleStrength, trailLength, trailFade, pop, pitch, volume;
    public final ModeSetting fadeEase, scaleEase;
    public final ParticlePool particles = new ParticlePool();
    private final HitParticleRenderer renderer = new HitParticleRenderer(this);
    private final JsonObject userPresets = new JsonObject();
    private boolean restoring;
    private ClientLevel world;
    private final Pending[] pending = new Pending[64];
    private int nextPending;
    private long ticks;
    private String presetStatus = "Eight saved slots";

    public HitParticles() {
        super("HitParticles", "Stars and sparks on confirmed local-player damage", Category.VISUAL);
        for (int i = 0; i < pending.length; i++) pending[i] = new Pending();
        group("general", "General", true);
        count = n("count", "Particles per hit", 14, 1, 128, 1);
        countRandom = n("countRandom", "Count random +/-", 2, 0, 64, 1);
        lifeMin = n("lifeMin", "Lifetime min (s)", .65, .1, 6, .05);
        lifeMax = n("lifeMax", "Lifetime max (s)", 1.1, .1, 6, .05);
        cap = n("cap", "Max live particles", 512, 16, ParticlePool.CAPACITY, 16);
        spawn = m("spawn", "Spawn position", "Hit point", "Hit point", "Body center", "Feet", "Eye height");
        spawnRadius = n("spawnRadius", "Spawn spread", .15, 0, 2, .01);
        trigger = m("trigger", "Trigger", "On hit", "On hit", "Crit only", "Kill only", "Any damage dealt");
        targets = add(new MultiSelectSetting("targets", "Targets", List.of("Players", "Hostile mobs", "Passive mobs"), "Players", "Hostile mobs", "Passive mobs", "All entities"));
        throughWalls = b("throughWalls", "Through walls", false);
        distance = n("distance", "Render distance", 64, 4, 256, 1);
        group("shapeSection", "Shape", false);
        shape = m("shape", "Shape", "Star", "Star", "Sparkle", "Circle", "Heart", "Diamond", "Cross", "Snowflake", "Triangle", "Ring", "Random mix");
        pool = add(new MultiSelectSetting("pool", "Random shape pool", List.of("Star", "Sparkle", "Ring"), SHAPES));
        points = m("points", "Star points", "5", "4", "5", "6", "8");
        inner = n("inner", "Star inner radius", .45, .1, .9, .01);
        size = n("size", "Base size (blocks)", .18, .02, 1.5, .01);
        sizeRandom = n("sizeRandom", "Size randomization", .3, 0, .95, .01);
        endSize = n("endSize", "End size multiplier", 0, 0, 3, .05);
        outline = b("outline", "Outline", false);
        outlineWidth = n("outlineWidth", "Outline thickness", .07, .01, .4, .01);
        outlineColor = c("outlineColor", "Outline color", 0xfff5ffff);
        group("colorSection", "Color", false);
        colorMode = m("colorMode", "Color mode", "Client accent", "Single", "Two-color gradient", "Multi-stop gradient", "Rainbow", "Random palette", "Client accent");
        startColor = c("startColor", "Start color", 0xff70d6db);
        endColor = c("endColor", "End color", 0xffb693ee);
        gradient = add(new GradientSetting("gradient", "Gradient stops", 0xff70d6db, 0xffb693ee, 0xfff494a5));
        palette = add(new GradientSetting("palette", "Palette colors", 0xff70d6db, 0xffb693ee, 0xfff494a5, 0xffffcf78));
        colorLifetime = b("colorLifetime", "Color over lifetime", true);
        rainbowSpeed = n("rainbowSpeed", "Rainbow speed", .2, 0, 3, .01);
        saturation = n("saturation", "Rainbow saturation", .65, 0, 1, .01);
        alphaStart = n("alphaStart", "Alpha start", 1, 0, 1, .01);
        alphaEnd = n("alphaEnd", "Alpha end", 0, 0, 1, .01);
        shapeColors = b("shapeColors", "Per-shape colors", false);
        for (int i = 0; i < SHAPES.length; i++) overrides[i] = c("color" + SHAPES[i], SHAPES[i] + " color", Color.HSBtoRGB(i / (float)SHAPES.length, .5f, 1));
        group("motionSection", "Motion", false);
        speedMin = n("speedMin", "Speed min (blocks/s)", .6, 0, 12, .05);
        speedMax = n("speedMax", "Speed max (blocks/s)", 1.5, 0, 12, .05);
        spread = n("spread", "Spread angle (degrees)", 150, 0, 180, 1);
        drag = n("drag", "Drag (per second)", 1.8, 0, 12, .1);
        gravity = n("gravity", "Gravity (blocks/s2)", .35, -8, 8, .05);
        motion = m("motion", "Motion mode", "Radial burst", "Radial burst", "Upward fountain", "Spiral", "Explosion", "Orbit");
        spin = n("spin", "Spin (degrees/s)", 100, -720, 720, 5);
        randomRotation = b("randomRotation", "Random initial rotation", true);
        randomSpin = b("randomSpin", "Random spin direction", true);
        billboard = b("billboard", "Face camera", true);
        group("effectsSection", "Effects", false);
        glow = n("glow", "Glow intensity", .35, 0, 2, .05);
        glowSize = n("glowSize", "Glow size", .6, .05, 2, .05);
        additive = b("additive", "Additive blending", false);
        twinkle = b("twinkle", "Twinkle / pulse", false);
        twinkleMode = m("twinkleMode", "Pulse affects", "Alpha", "Alpha", "Scale");
        twinkleSpeed = n("twinkleSpeed", "Twinkle speed (Hz)", 3, .1, 20, .1);
        twinkleStrength = n("twinkleStrength", "Twinkle strength", .3, 0, 1, .01);
        trail = b("trail", "Particle trails", false);
        trailLength = n("trailLength", "Trail length (samples)", 6, 1, ParticlePool.TRAIL, 1);
        trailFade = n("trailFade", "Trail fade", .65, .1, 3, .05);
        fadeEase = m("fadeEase", "Fade easing", "Ease-out", EASINGS);
        scaleEase = m("scaleEase", "Scale easing", "Back", EASINGS);
        pop = n("pop", "Pop-in duration (s)", .12, .02, .5, .01);
        group("soundSection", "Sound", false);
        sound = b("sound", "Hit sound", false);
        pitch = n("pitch", "Sound pitch", 1.6, .5, 2, .05);
        volume = n("volume", "Sound volume", .15, 0, 1, .01);
        group("presetsSection", "Presets", true);
        preset = m("preset", "Built-in look", "Starburst", "Starburst", "Sparkle", "Hearts", "Galaxy", "Confetti", "Minimal", "Custom");
        userSlot = m("userSlot", "User preset slot", "User 1", "User 1", "User 2", "User 3", "User 4", "User 5", "User 6", "User 7", "User 8");
        add(new ActionSetting("savePreset", "Save selected slot", this::savePreset));
        add(new ActionSetting("loadPreset", "Load selected slot", this::loadPreset));
    }
    private NumberSetting n(String key, String label, double value, double min, double max, double step) { return add(new NumberSetting(key, label, value, min, max, step)); }
    private ModeSetting m(String key, String label, String value, String... options) { return add(new ModeSetting(key, label, value, options)); }
    private BooleanSetting b(String key, String label, boolean value) { return add(new BooleanSetting(key, label, value)); }
    private ColorSetting c(String key, String label, int value) { return add(new ColorSetting(key, label, value)); }
    private boolean lookSetting(Setting<?> s) { return !(s instanceof GroupSetting || s instanceof ActionSetting) && s != preset && s != userSlot; }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (restoring || preset == null) return;
        if (setting == preset) { if (!preset.get().equals("Custom")) applyPreset(preset.get()); }
        else if (lookSetting(setting)) { restoring = true; preset.set("Custom"); restoring = false; }
    }
    @Override public void beginRestore() { restoring = true; }
    @Override public void endRestore() { restoring = false; }
    private void applyPreset(String name) {
        restoring = true;
        for (Setting<?> s : settings()) if (lookSetting(s)) s.reset();
        switch (name) {
            case "Sparkle" -> { shape.set("Sparkle"); twinkle.set(true); glow.set(.7); gravity.set(-.2); }
            case "Hearts" -> { shape.set("Heart"); colorMode.set("Two-color gradient"); startColor.set(0xffff719f); endColor.set(0xffffc0d3); motion.set("Upward fountain"); gravity.set(-.3); spin.set(25.0); }
            case "Galaxy" -> { shape.set("Random mix"); colorMode.set("Multi-stop gradient"); motion.set("Spiral"); trail.set(true); twinkle.set(true); additive.set(true); glow.set(.7); lifeMin.set(1.2); lifeMax.set(1.8); }
            case "Confetti" -> { shape.set("Random mix"); pool.set(List.of("Star", "Diamond", "Cross", "Triangle")); colorMode.set("Random palette"); count.set(28.0); speedMax.set(3.0); spin.set(300.0); gravity.set(2.0); glow.set(.1); }
            case "Minimal" -> { shape.set("Circle"); count.set(6.0); size.set(.08); colorMode.set("Single"); glow.set(.1); spin.set(0.0); }
            default -> { }
        }
        restoring = false;
    }
    private void savePreset() {
        JsonObject values = new JsonObject();
        for (Setting<?> s : settings()) if (lookSetting(s)) SettingCodec.write(values, s);
        userPresets.add(userSlot.get(), values); presetStatus = "Saved " + userSlot.get(); changed();
    }
    private void loadPreset() {
        if (!userPresets.has(userSlot.get())) { presetStatus = "Empty: " + userSlot.get(); return; }
        restoring = true;
        JsonObject values = userPresets.getAsJsonObject(userSlot.get());
        for (Setting<?> s : settings()) if (lookSetting(s)) SettingCodec.read(values, s);
        preset.set("Custom"); restoring = false;
        presetStatus = "Loaded " + userSlot.get(); changed();
    }
    public String presetStatus() { return presetStatus; }
    @Override public JsonObject extraConfig() { return userPresets.deepCopy(); }
    @Override public void extraConfig(JsonObject json) {
        for (String name : userSlot.options()) if (json.has(name) && json.get(name).isJsonObject()) userPresets.add(name, json.get(name).deepCopy());
    }
    @Override public void initialize() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Minecraft mc = Minecraft.getInstance();
            if (level.isClientSide() && enabled() && player == mc.player && !player.isSpectator()) {
                Vec3 position = hit != null ? hit.getLocation() : mc.hitResult instanceof EntityHitResult result && result.getEntity() == entity ? result.getLocation() : entity.position().add(0, entity.getBbHeight() * .5, 0);
                Pending p = entry(entity.getId());
                p.attackTick = ticks; p.hitX = position.x; p.hitY = position.y; p.hitZ = position.z;
                p.critical = player.fallDistance > 0 && !player.onGround() && !player.onClimbable() && !player.isInWater() && !player.isPassenger() && !player.isSprinting() && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) && player.getAttackStrengthScale(.5f) > .9f;
            }
            return InteractionResult.PASS;
        });
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract);
        WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { renderer.close(); ScrimmyClient.CONFIG.save(); });
    }
    private Pending entry(int id) {
        for (Pending p : pending) if (p.id == id) return p;
        Pending p = pending[nextPending++ % pending.length];
        p.id = id; p.attackTick = p.damageTick = -1000; p.waitingKill = false;
        return p;
    }
    /** Called after vanilla has handled a server damage packet on the client thread. */
    public void damage(int entityId, int causeId, int directId) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.player == null || mc.level == null) return;
        Entity entity = mc.level.getEntity(entityId);
        if (entity == null || entity == mc.player || !accepts(entity)) return;
        if (causeId != mc.player.getId() && directId != mc.player.getId()) {
            for (Pending pendingHit : pending) if (pendingHit.id == entityId) pendingHit.waitingKill = false;
            return;
        }
        Pending p = entry(entityId);
        boolean melee = ticks - p.attackTick <= 10 && p.attackTick > -1000;
        if (p.damageTick == ticks) return;
        p.damageTick = ticks;
        switch (trigger.get()) {
            case "On hit" -> { if (melee) burst(entity, p); }
            case "Crit only" -> { if (melee && p.critical) burst(entity, p); }
            case "Kill only" -> p.waitingKill = true;
            default -> burst(entity, p);
        }
        p.attackTick = -1000;
    }
    public void death(Entity entity) {
        if (!enabled() || entity == null || !accepts(entity)) return;
        for (Pending p : pending) if (p.id == entity.getId() && p.waitingKill && ticks - p.damageTick <= 40) {
            burst(entity, p); p.waitingKill = false; break;
        }
    }
    private boolean accepts(Entity entity) {
        List<String> selected = targets.get();
        if (selected.contains("All entities")) return true;
        if (entity instanceof Player) return selected.contains("Players");
        if (!(entity instanceof LivingEntity)) return false;
        return selected.contains(entity.getType().getCategory() == MobCategory.MONSTER ? "Hostile mobs" : "Passive mobs");
    }
    @Override public void onTick(Minecraft mc) {
        if (mc.level != world) { clear(); world = mc.level; }
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        ticks++;
        particles.cap(cap.get().intValue());
        for (Pending p : pending) if (p.waitingKill) {
            Entity entity = mc.level.getEntity(p.id);
            if (ticks - p.damageTick > 40 || entity == null) p.waitingKill = false;
            else if (entity instanceof LivingEntity living && living.isDeadOrDying()) death(entity);
        }
        if (motion.get().equals("Orbit")) for (int i = 0; i < particles.size(); i++) {
            ParticlePool.Particle p = particles.get(i);
            Entity target = mc.level.getEntity(p.targetId);
            if (target != null) { p.originX = target.getX(); p.originY = target.getY() + target.getBbHeight() * .5; p.originZ = target.getZ(); }
        }
        particles.tick(.05, motion.get(), drag.get(), gravity.get());
    }
    private void burst(Entity entity, Pending hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(entity) > distance.get() * distance.get()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        switch (spawn.get()) {
            case "Hit point" -> {
                if (ticks - hit.attackTick <= 10 && hit.attackTick > -1000) { x = hit.hitX; y = hit.hitY; z = hit.hitZ; }
                else y += entity.getBbHeight() * .5;
            }
            case "Body center" -> y += entity.getBbHeight() * .5;
            case "Eye height" -> y = entity.getEyeY();
            default -> { }
        }
        int amount = Math.max(0, count.get().intValue() + r.nextInt(-countRandom.get().intValue(), countRandom.get().intValue() + 1));
        for (int i = 0; i < amount; i++) {
            ParticlePool.Particle p = particles.acquire(cap.get().intValue()); if (p == null) break;
            // Uniform solid-angle sampling avoids bunching at the cone's pole.
            double azimuth = r.nextDouble(Math.PI * 2), cone = Math.toRadians(spread.get());
            double cos = 1 - r.nextDouble() * (1 - Math.cos(cone)), sin = Math.sqrt(Math.max(0, 1 - cos * cos));
            double dx = sin * Math.cos(azimuth), dy = cos, dz = sin * Math.sin(azimuth);
            if (!motion.get().equals("Upward fountain")) {
                double ax = entity.getX() - mc.player.getX(), az = entity.getZ() - mc.player.getZ();
                double length = Math.hypot(ax, az);
                if (length < .0001) { ax = 0; az = 1; } else { ax /= length; az /= length; }
                double lateral = dx;
                dx = ax * dy - az * lateral; dz = az * dy + ax * lateral; dy = sin * Math.sin(azimuth);
            }
            double speed = between(r, speedMin.get(), speedMax.get()) * (motion.get().equals("Explosion") ? 2 : 1);
            double radius = spawnRadius.get() * Math.cbrt(r.nextDouble());
            p.reset(x + dx * radius, y + dy * radius, z + dz * radius);
            p.vx = dx * speed; p.vy = dy * speed; p.vz = dz * speed;
            p.lifetime = between(r, lifeMin.get(), lifeMax.get());
            p.baseSize = size.get();
            p.size = size.get() * (1 + (r.nextDouble() * 2 - 1) * sizeRandom.get());
            p.angle = p.oldAngle = randomRotation.get() ? r.nextDouble(Math.PI * 2) : 0;
            p.spin = Math.toRadians(spin.get()) * (randomSpin.get() && r.nextBoolean() ? -1 : 1);
            p.phase = r.nextDouble(Math.PI * 2); p.orbitRadius = Math.max(.05, speed * .35 + radius); p.colorSeed = r.nextDouble(); p.targetId = entity.getId();
            String chosen = shape.get().equals("Random mix") ? pool.get().get(r.nextInt(pool.get().size())) : shape.get();
            p.shape = 0; for (int s = 0; s < SHAPES.length; s++) if (SHAPES[s].equals(chosen)) p.shape = s;
            if (motion.get().equals("Orbit")) {
                p.originX = entity.getX(); p.originY = entity.getY() + entity.getBbHeight() * .5; p.originZ = entity.getZ();
                p.x = p.oldX = p.originX + Math.cos(p.phase) * p.orbitRadius;
                p.z = p.oldZ = p.originZ + Math.sin(p.phase) * p.orbitRadius;
                p.y = p.oldY = p.originY + Math.sin(p.phase * 1.5) * p.orbitRadius * .4;
            }
        }
        if (sound.get()) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, volume.get().floatValue(), pitch.get().floatValue());
    }
    private static double between(ThreadLocalRandom r, double a, double b) { return Math.min(a, b) + r.nextDouble() * Math.abs(b - a); }
    public int color(ParticlePool.Particle p, double progress, double seconds) {
        if (shapeColors.get()) return overrides[p.shape].get();
        double f = colorLifetime.get() ? progress : p.colorSeed;
        return switch (colorMode.get()) {
            case "Single" -> colorLifetime.get() ? GradientSetting.mix(startColor.get(), endColor.get(), progress) : startColor.get();
            case "Two-color gradient" -> GradientSetting.mix(startColor.get(), endColor.get(), f);
            case "Multi-stop gradient" -> gradient.sample(f);
            case "Rainbow" -> Color.HSBtoRGB((float)((p.colorSeed + seconds * rainbowSpeed.get()) % 1), saturation.get().floatValue(), 1);
            case "Random palette" -> palette.get().get(Math.min(palette.get().size() - 1, (int)(p.colorSeed * palette.get().size()))).color();
            default -> GradientSetting.mix(ScrimmyClient.THEME.accent.get(), 0xfff4faff, f * .75);
        };
    }
    private void clear() {
        particles.clear(); world = null; nextPending = 0; ticks = 0;
        for (Pending p : pending) { p.id = -1; p.attackTick = p.damageTick = -1000; p.waitingKill = false; }
        renderer.close();
    }
    @Override protected void onDisable() { clear(); }
    private static final class Pending {
        int id = -1;
        long attackTick = -1000, damageTick = -1000;
        double hitX, hitY, hitZ;
        boolean critical, waitingKill;
    }
}
