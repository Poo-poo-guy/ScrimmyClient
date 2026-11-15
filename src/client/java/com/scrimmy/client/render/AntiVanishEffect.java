package com.scrimmy.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;
import java.util.Locale;

/** Owns one warning timeline; the head is a real perspective cube in a vanilla GUI preview. */
public final class AntiVanishEffect {
    public static final String CLOSING_NOTE = "GOOD LUCK, SCRIMMY USER";
    public static final double DEFAULT_HEAD_SIZE = 1;
    public static final double DEFAULT_SPIN_TURNS = 1.5;
    static final double HEAD_DURATION = 1.5;
    static final double HEAD_RUSH_START = .85;
    static final float HEAD_START_SCALE = .12f, HEAD_END_SCALE = 1;
    static final float HEAD_START_DISTANCE = 5, HEAD_END_DISTANCE = 3;
    static final float HEAD_ROLL = .12f, HEAD_PITCH = .16f;
    static final float HEAD_HAT_SCALE = 1.125f, HEAD_NEAR_MARGIN = .08f;
    static final float HEAD_FOV_DEGREES = 45, HEAD_NEAR_PLANE = .05f, HEAD_FAR_PLANE = 100;
    static final double TITLE_START = 1.25, TITLE_FADE_IN = .5, TITLE_HOLD = 2, TITLE_FADE_OUT = .75;
    static final double NOTE_START = 4.5, NOTE_FADE_IN = .5, NOTE_HOLD = 2.5, NOTE_FADE_OUT = 1;
    private static final float TITLE_SCALE = 3, NOTE_SCALE = 2, TEXT_WIDTH_FRACTION = .92f;
    private static final int TITLE_COLOR = 0xFF5555, NOTE_COLOR = 0xFFFF55, MIN_TEXT_ALPHA = 8;
    private static final float SOUND_PITCH = 1;
    private Identifier texture;
    private String title;
    private long started;
    private float headSize, spinTurns;
    private SimpleSoundInstance warningSound;

    public void initialize() {
        SpecialGuiElementRegistry.register(context -> new AntiVanishHeadRenderer(context.vertexConsumers()));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "anti_vanish"), (graphics, delta) -> hud(graphics));
    }
    public void warn(String name, PlayerSkin skin, boolean sound, float size, float turns) {
        stop();
        texture = skin.body().texturePath();
        title = name.toUpperCase(Locale.ROOT) + " IS WATCHING YOU";
        headSize = size; spinTurns = turns;
        started = System.nanoTime();
        if (sound) {
            warningSound = SimpleSoundInstance.forUI(SoundEvents.ELDER_GUARDIAN_CURSE, SOUND_PITCH);
            Minecraft.getInstance().getSoundManager().play(warningSound);
        }
    }
    public void stop() {
        texture = null; title = null;
        if (warningSound != null) {
            Minecraft.getInstance().getSoundManager().stop(warningSound);
            warningSound = null;
        }
    }
    private void hud(GuiGraphics g) {
        if (texture == null) return;
        double age = (System.nanoTime() - started) / 1_000_000_000.0;
        if (age >= NOTE_START + NOTE_FADE_IN + NOTE_HOLD + NOTE_FADE_OUT) { stop(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (age < HEAD_DURATION) {
            HeadMotion motion = headMotion(age / HEAD_DURATION, headSize, spinTurns);
            g.guiRenderState.submitPicturesInPictureState(new AntiVanishHeadRenderer.HeadState(
                texture, g.guiWidth(), g.guiHeight(), motion.size(), motion.distance(), motion.yaw(), motion.pitch(), motion.roll(), g.scissorStack.peek()));
        }
        text(g, title, TITLE_COLOR, TITLE_SCALE, opacity(age, TITLE_START, TITLE_FADE_IN, TITLE_HOLD, TITLE_FADE_OUT));
        text(g, CLOSING_NOTE, NOTE_COLOR, NOTE_SCALE, opacity(age, NOTE_START, NOTE_FADE_IN, NOTE_HOLD, NOTE_FADE_OUT));
    }
    private static void text(GuiGraphics g, String text, int color, float requestedScale, double opacity) {
        int alpha = (int)(255 * opacity);
        if (alpha < MIN_TEXT_ALPHA) return;
        var font = Minecraft.getInstance().font;
        int width = font.width(text);
        float scale = Math.min(requestedScale, g.guiWidth() * TEXT_WIDTH_FRACTION / Math.max(1, width));
        g.pose().pushMatrix();
        try {
            g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 2f);
            g.pose().scale(scale, scale);
            g.drawString(font, text, -width / 2, -font.lineHeight / 2, (alpha << 24) | color, false);
        } finally { g.pose().popMatrix(); }
    }
    static double opacity(double age, double start, double fadeIn, double hold, double fadeOut) {
        double elapsed = age - start;
        if (elapsed < 0) return 0;
        if (elapsed < fadeIn) return elapsed / fadeIn;
        if (elapsed < fadeIn + hold) return 1;
        return Math.max(0, 1 - (elapsed - fadeIn - hold) / fadeOut);
    }
    static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }
    static HeadMotion headMotion(double progress, float headSize, float spinTurns) {
        float approach = (float)smooth(progress / HEAD_RUSH_START);
        float rush = (float)smooth((progress - HEAD_RUSH_START) / (1 - HEAD_RUSH_START));
        float size = headSize * (HEAD_START_SCALE + (HEAD_END_SCALE - HEAD_START_SCALE) * approach);
        float distance = HEAD_START_DISTANCE + (HEAD_END_DISTANCE - HEAD_START_DISTANCE) * approach;
        // The rush stops just in front of the near plane, even at the largest head setting.
        distance += (size * HEAD_HAT_SCALE / 2 + HEAD_NEAR_MARGIN - distance) * rush;
        float wobble = (float)Math.sin(progress * Math.PI);
        float yaw = (float)(-Math.PI * 2 * spinTurns * (1 - approach));
        return new HeadMotion(size, distance, yaw, HEAD_PITCH * wobble * (1 - rush), HEAD_ROLL * wobble * (1 - rush));
    }
    record HeadMotion(float size, float distance, float yaw, float pitch, float roll) {}
}
