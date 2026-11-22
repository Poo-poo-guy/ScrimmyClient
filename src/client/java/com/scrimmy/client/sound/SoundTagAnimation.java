package com.scrimmy.client.sound;

import com.scrimmy.client.animation.Easing;

/** One source's reversible nametag timeline. All time values are seconds; stepping allocates nothing. */
public final class SoundTagAnimation {
    public static final int LINES = 5;
    public static final double LINE_DELAY = .030;
    private final double[] lines = new double[LINES], startLines = new double[LINES];
    private double progress, startProgress, elapsed, duration, enterAge, enterDuration, lingerRemaining;
    private double alpha, scale = .85, slide = 4, startAlpha, startScale, startSlide, pulsePhase;
    private boolean targetVisible, requested;

    public enum Style { FADE, POP, SLIDE, GLASS_EXPAND }
    public static Style style(String name) {
        return switch (name) {
            case "Fade" -> Style.FADE;
            case "Slide" -> Style.SLIDE;
            case "Glass expand" -> Style.GLASS_EXPAND;
            default -> Style.POP;
        };
    }
    public double progress() { return progress; }
    public double alpha() { return alpha; }
    public double lineAlpha(int line) { return lines[line]; }
    public boolean hiding() { return !targetVisible; }
    public double scaleX(Style style) { return style == Style.POP || style == Style.GLASS_EXPAND ? scale : 1; }
    public double scaleY(Style style) { return style == Style.GLASS_EXPAND ? 1 + (scale - 1) * .5 : scaleX(style); }
    public double slide(Style style) { return style == Style.FADE ? 0 : slide; }
    /** A 2.8-second, +/-15% glow cycle; only the Red flag renderer consumes it. */
    public double pulse() { return 1 + .15 * Math.sin(pulsePhase); }

    public void step(boolean show, boolean mayLinger, double dt, double in, double out, double linger) {
        dt = Math.max(0, dt);
        if (show) {
            lingerRemaining = 0;
            if (!targetVisible) begin(true, in);
        } else if (targetVisible) {
            if (!mayLinger) lingerRemaining = 0;
            else if (requested) lingerRemaining = Math.max(0, linger);
            double held = Math.min(dt, lingerRemaining);
            advance(held);
            lingerRemaining -= held; dt -= held;
            // Carry the part of a long frame after the linger deadline into the exit animation.
            if (lingerRemaining <= 1e-9) begin(false, out);
        }
        advance(dt);
        requested = show;
    }
    private void begin(boolean visible, double seconds) {
        if (visible && progress == 0) {
            alpha = 0; scale = .85; slide = 4;
            java.util.Arrays.fill(lines, 0);
        }
        targetVisible = visible; startProgress = progress; elapsed = 0;
        duration = Math.max(0, seconds) * (visible ? 1 - progress : progress);
        startAlpha = alpha; startScale = scale; startSlide = slide;
        System.arraycopy(lines, 0, startLines, 0, LINES);
        if (visible) { enterAge = 0; enterDuration = Math.max(0, seconds); }
    }
    private void advance(double dt) {
        if (dt > 0 && (targetVisible || progress > 0))
            pulsePhase = (pulsePhase + dt * (2 * Math.PI / 2.8)) % (2 * Math.PI);
        elapsed = Math.min(duration, elapsed + dt);
        if (duration - elapsed < 1e-9) elapsed = duration;
        double t = duration == 0 ? 1 : Math.clamp(elapsed / duration, 0, 1);
        progress = Math.clamp(startProgress + ((targetVisible ? 1 : 0) - startProgress) * t, 0, 1);
        if (targetVisible) {
            enterAge += dt;
            double eased = Easing.OUT_CUBIC.apply(t);
            alpha = startAlpha + (1 - startAlpha) * eased;
            // Shared back easing approaches a small crest, then settles during the final 40ms
            // of the default entrance. Retargeting starts from the current shape, without jumps.
            scale = t < .8 ? startScale + (1.018 - startScale) * Easing.OUT_BACK.apply(t / .8)
                    : 1.018 - .018 * Easing.OUT_CUBIC.apply((t - .8) / .2);
            slide = startSlide * (1 - eased);
            for (int i = 0; i < LINES; i++) {
                double line = enterDuration == 0 ? 1 : Math.clamp((enterAge - LINE_DELAY * i) / enterDuration, 0, 1);
                lines[i] = startLines[i] + (1 - startLines[i]) * Easing.OUT_CUBIC.apply(line);
            }
        } else {
            double eased = 1 - Easing.OUT_CUBIC.apply(1 - t);
            alpha = startAlpha * (1 - eased);
            scale = startScale + (.92 - startScale) * eased;
            slide = startSlide + (3 - startSlide) * eased;
            for (int i = 0; i < LINES; i++) lines[i] = startLines[i] * (1 - eased);
        }
    }
}
