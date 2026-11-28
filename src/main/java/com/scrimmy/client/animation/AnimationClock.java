package com.scrimmy.client.animation;

/** Sample once at GameRenderer.render HEAD; every client UI shares this time step. */
public final class AnimationClock {
    public static final AnimationClock UI = new AnimationClock();
    private long previous;
    private double delta;
    public void frame(long now) {
        delta = previous == 0 ? 0 : Math.max(0, (now - previous) * 1e-9);
        previous = now;
    }
    public double delta() { return delta; }
}
