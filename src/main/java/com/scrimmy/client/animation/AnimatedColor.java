package com.scrimmy.client.animation;

/** Keeps fractional channels between frames so slow transitions cannot stall on integer rounding. */
public final class AnimatedColor {
    private final Animation[] channels = new Animation[4];
    public AnimatedColor(int initial) {
        for (int i = 0; i < 4; i++) channels[i] = new Animation((initial >>> (i * 8)) & 255);
    }
    public int update(int target, double dt, double frequency, double damping) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            float value = channels[i].spring((target >>> (i * 8)) & 255, dt, frequency, damping);
            result |= Math.clamp(Math.round(value), 0, 255) << (i * 8);
        }
        return result;
    }
}
