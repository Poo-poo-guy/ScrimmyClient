package com.scrimmy.client.module.visual;

import com.scrimmy.client.ScrimmyClient;
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
    public final NumberSetting count, size;
    public final ModeSetting trigger, preset;
    public final MultiSelectSetting targets;
    public final BooleanSetting throughWalls;
    // Fixed tuning and mutable style data replace the former fine-grained controls.
    public static final double COUNT_RANDOM = 2;
    public double lifeMin = .65;
    public double lifeMax = 1.1;
    public static final double CAP = 512;
    public static final String SPAWN = "Hit point";
    public static final double SPAWN_RADIUS = .15;
    public static final double DISTANCE = 64;
    public String shape = "Star";
    public static final String POINTS = "5";
    public static final double INNER = .45;
    public static final double SIZE_RANDOM = .3;
    public static final double END_SIZE = 0;
    public static final boolean OUTLINE = false;
    public static final double OUTLINE_WIDTH = .07;
    public static final int OUTLINE_COLOR = 0xfff5ffff;
    public String colorMode = "Client accent";
    public int startColor = 0xff70d6db;
    public int endColor = 0xffb693ee;
    public static final boolean COLOR_LIFETIME = true;
    public static final double RAINBOW_SPEED = .2;
    public static final double SATURATION = .65;
    public static final double ALPHA_START = 1;
    public static final double ALPHA_END = 0;
    public static final boolean SHAPE_COLORS = false;
    public static final double SPEED_MIN = .6;
    public double speedMax = 1.5;
    public static final double SPREAD = 150;
    public static final double DRAG = 1.8;
    public double gravity = .35;
    public String motion = "Radial burst";
    public double spin = 100;
    public static final boolean RANDOM_ROTATION = true;
    public static final boolean RANDOM_SPIN = true;
    public static final boolean BILLBOARD = true;
    public double glow = .35;
    public static final double GLOW_SIZE = .6;
    public boolean additive = false;
    public boolean twinkle = false;
    public static final String TWINKLE_MODE = "Alpha";
    public static final double TWINKLE_SPEED = 3;
    public static final double TWINKLE_STRENGTH = .3;
    public boolean trail = false;
    public static final double TRAIL_LENGTH = 6;
    public static final double TRAIL_FADE = .65;
    public static final String FADE_EASE = "Ease-out";
    public static final String SCALE_EASE = "Back";
    public static final double POP = .12;
    public static final boolean SOUND = false;
    public static final double PITCH = 1.6;
    public static final double VOLUME = .15;
    public List<String> pool = List.of("Star", "Sparkle", "Ring");
    public final GradientSetting gradient = new GradientSetting("gradient", "Gradient", 0xff70d6db, 0xffb693ee, 0xfff494a5);
    public final GradientSetting palette = new GradientSetting("palette", "Palette", 0xff70d6db, 0xffb693ee, 0xfff494a5, 0xffffcf78);
    public final int[] overrides = new int[SHAPES.length];
    public final ParticlePool particles = new ParticlePool();
    private final HitParticleRenderer renderer = new HitParticleRenderer(this);
    private boolean restoring;
    private ClientLevel world;
    private final Pending[] pending = new Pending[64];
    private int nextPending;
    private long ticks;

    public HitParticles() {
        super("HitParticles", "Stars and sparks on confirmed local-player damage", Category.VISUAL);
        for (int i = 0; i < pending.length; i++) pending[i] = new Pending();
        preset = m("preset", "Style", "Starburst", "Starburst", "Sparkle", "Hearts", "Galaxy", "Confetti", "Minimal");
        count = n("count", "Amount", 14, 1, 128, 1);
        trigger = m("trigger", "Trigger", "On hit", "On hit", "Crit only", "Kill only", "Any damage dealt");
        targets = add(new MultiSelectSetting("targets", "Targets", List.of("Players", "Hostile mobs", "Passive mobs"), "Players", "Hostile mobs", "Passive mobs", "All entities"));
        throughWalls = b("throughWalls", "Through walls", false);
        size = n("size", "Size", .18, .02, 1.5, .01);
        count.tooltip("How many particles appear for each hit.");
        size.tooltip("How large each particle looks.");
        trigger.tooltip("Choose which hits create particles.");
        targets.tooltip("Choose which kinds of entities create particles.");
        throughWalls.tooltip("Show particles behind solid blocks.");
        preset.tooltip("Choose a complete particle style.");
    }
    private NumberSetting n(String key, String label, double value, double min, double max, double step) { return add(new NumberSetting(key, label, value, min, max, step)); }
    private ModeSetting m(String key, String label, String value, String... options) { return add(new ModeSetting(key, label, value, options)); }
    private BooleanSetting b(String key, String label, boolean value) { return add(new BooleanSetting(key, label, value)); }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if (restoring || preset == null) return;
        if (setting == preset) applyPreset(preset.get());
    }
    @Override public void beginRestore() { restoring = true; }
    @Override public void endRestore() {
        double savedCount = count.get(), savedSize = size.get(); boolean savedWalls = throughWalls.get();
        applyPreset(preset.get());
        restoring = true; count.set(savedCount); size.set(savedSize); throughWalls.set(savedWalls); restoring = false;
    }
    private void resetTuning() {
        lifeMin = .65;
        lifeMax = 1.1;
        shape = "Star";
        colorMode = "Client accent";
        startColor = 0xff70d6db;
        endColor = 0xffb693ee;
        speedMax = 1.5;
        gravity = .35;
        motion = "Radial burst";
        spin = 100;
        glow = .35;
        additive = false;
        twinkle = false;
        trail = false;
        pool = List.of("Star", "Sparkle", "Ring");
    }
    private void applyPreset(String name) {
        restoring = true;
        resetTuning(); count.reset(); size.reset(); throughWalls.reset();
        switch (name) {
            case "Sparkle" -> { shape = "Sparkle"; twinkle = true; glow = .7; gravity = -.2; }
            case "Hearts" -> { shape = "Heart"; colorMode = "Two-color gradient"; startColor = 0xffff719f; endColor = 0xffffc0d3; motion = "Upward fountain"; gravity = -.3; spin = 25; }
            case "Galaxy" -> { shape = "Random mix"; colorMode = "Multi-stop gradient"; motion = "Spiral"; trail = true; twinkle = true; additive = true; glow = .7; lifeMin = 1.2; lifeMax = 1.8; }
            case "Confetti" -> { shape = "Random mix"; pool = List.of("Star", "Diamond", "Cross", "Triangle"); colorMode = "Random palette"; count.set(28.0); speedMax = 3; spin = 300; gravity = 2; glow = .1; }
            case "Minimal" -> { shape = "Circle"; count.set(6.0); size.set(.08); colorMode = "Single"; glow = .1; spin = 0; }
            default -> { }
        }
        restoring = false;
    }
    public String presetStatus() { return "Choose a style, then adjust amount and size."; }
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
        particles.cap((int) CAP);
        for (Pending p : pending) if (p.waitingKill) {
            Entity entity = mc.level.getEntity(p.id);
            if (ticks - p.damageTick > 40 || entity == null) p.waitingKill = false;
            else if (entity instanceof LivingEntity living && living.isDeadOrDying()) death(entity);
        }
        if (motion.equals("Orbit")) for (int i = 0; i < particles.size(); i++) {
            ParticlePool.Particle p = particles.get(i);
            Entity target = mc.level.getEntity(p.targetId);
            if (target != null) { p.originX = target.getX(); p.originY = target.getY() + target.getBbHeight() * .5; p.originZ = target.getZ(); }
        }
        particles.tick(.05, motion, DRAG, gravity);
    }
    private void burst(Entity entity, Pending hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(entity) > DISTANCE * DISTANCE) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        switch (SPAWN) {
            case "Hit point" -> {
                if (ticks - hit.attackTick <= 10 && hit.attackTick > -1000) { x = hit.hitX; y = hit.hitY; z = hit.hitZ; }
                else y += entity.getBbHeight() * .5;
            }
            case "Body center" -> y += entity.getBbHeight() * .5;
            case "Eye height" -> y = entity.getEyeY();
            default -> { }
        }
        int amount = Math.max(0, count.get().intValue() + r.nextInt(-(int) COUNT_RANDOM, (int) COUNT_RANDOM + 1));
        for (int i = 0; i < amount; i++) {
            ParticlePool.Particle p = particles.acquire((int) CAP); if (p == null) break;
            // Uniform solid-angle sampling avoids bunching at the cone's pole.
            double azimuth = r.nextDouble(Math.PI * 2), cone = Math.toRadians(SPREAD);
            double cos = 1 - r.nextDouble() * (1 - Math.cos(cone)), sin = Math.sqrt(Math.max(0, 1 - cos * cos));
            double dx = sin * Math.cos(azimuth), dy = cos, dz = sin * Math.sin(azimuth);
            if (!motion.equals("Upward fountain")) {
                double ax = entity.getX() - mc.player.getX(), az = entity.getZ() - mc.player.getZ();
                double length = Math.hypot(ax, az);
                if (length < .0001) { ax = 0; az = 1; } else { ax /= length; az /= length; }
                double lateral = dx;
                dx = ax * dy - az * lateral; dz = az * dy + ax * lateral; dy = sin * Math.sin(azimuth);
            }
            double speed = between(r, SPEED_MIN, speedMax) * (motion.equals("Explosion") ? 2 : 1);
            double radius = SPAWN_RADIUS * Math.cbrt(r.nextDouble());
            p.reset(x + dx * radius, y + dy * radius, z + dz * radius);
            p.vx = dx * speed; p.vy = dy * speed; p.vz = dz * speed;
            p.lifetime = between(r, lifeMin, lifeMax);
            p.baseSize = size.get();
            p.size = size.get() * (1 + (r.nextDouble() * 2 - 1) * SIZE_RANDOM);
            p.angle = p.oldAngle = RANDOM_ROTATION ? r.nextDouble(Math.PI * 2) : 0;
            p.spin = Math.toRadians(spin) * (RANDOM_SPIN && r.nextBoolean() ? -1 : 1);
            p.phase = r.nextDouble(Math.PI * 2); p.orbitRadius = Math.max(.05, speed * .35 + radius); p.colorSeed = r.nextDouble(); p.targetId = entity.getId();
            String chosen = shape.equals("Random mix") ? pool.get(r.nextInt(pool.size())) : shape;
            p.shape = 0; for (int s = 0; s < SHAPES.length; s++) if (SHAPES[s].equals(chosen)) p.shape = s;
            if (motion.equals("Orbit")) {
                p.originX = entity.getX(); p.originY = entity.getY() + entity.getBbHeight() * .5; p.originZ = entity.getZ();
                p.x = p.oldX = p.originX + Math.cos(p.phase) * p.orbitRadius;
                p.z = p.oldZ = p.originZ + Math.sin(p.phase) * p.orbitRadius;
                p.y = p.oldY = p.originY + Math.sin(p.phase * 1.5) * p.orbitRadius * .4;
            }
        }
        if (SOUND) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, (float) VOLUME, (float) PITCH);
    }
    private static double between(ThreadLocalRandom r, double a, double b) { return Math.min(a, b) + r.nextDouble() * Math.abs(b - a); }
    public int color(ParticlePool.Particle p, double progress, double seconds) {
        if (SHAPE_COLORS) return overrides[p.shape];
        double f = COLOR_LIFETIME ? progress : p.colorSeed;
        return switch (colorMode) {
            case "Single" -> COLOR_LIFETIME ? GradientSetting.mix(startColor, endColor, progress) : startColor;
            case "Two-color gradient" -> GradientSetting.mix(startColor, endColor, f);
            case "Multi-stop gradient" -> gradient.sample(f);
            case "Rainbow" -> Color.HSBtoRGB((float)((p.colorSeed + seconds * RAINBOW_SPEED) % 1), (float) SATURATION, 1);
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
