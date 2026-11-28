package com.scrimmy.client.notification;

/** Client-tick rate limiting and bounded pitch selection, independent of rendering or world time. */
public final class NotificationSoundLimiter {
    private long tick;
    private long playedTick = Long.MIN_VALUE;
    public void beginTick() { tick++; }
    public boolean claim(boolean audible) {
        if (!audible || playedTick == tick) return false;
        playedTick = tick;
        return true;
    }
    public static float pitch(boolean enabled, boolean variation, float sample) {
        float base = enabled ? 1.0f : .82f;
        return base + (variation ? (Math.clamp(sample, 0, 1) * 2 - 1) * .035f : 0);
    }
}
