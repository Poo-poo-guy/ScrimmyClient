package com.scrimmy.client.sound;

/** Reused camera-cone selection. The animation owns linger; exiting the outer cone alone never snaps a tag off. */
public final class SoundTagSelection<T> {
    private T active, nearest;
    private double enterCosine, exitCosine, nearestDistance;
    private boolean available, held;
    public T active() { return active; }
    public void clear() { active = nearest = null; available = held = false; }
    public void begin(double angle, double margin) {
        nearest = null; nearestDistance = Double.MAX_VALUE; available = held = false;
        enterCosine = Math.cos(Math.toRadians(angle)); exitCosine = Math.cos(Math.toRadians(angle + margin));
    }
    /** Consider only display-eligible sources, with normalized camera dot product and current distance. */
    public void consider(T source, double distance, double cosine) {
        if (distance < .001) return;
        if (source == active) { available = true; held = cosine >= exitCosine; }
        if (cosine >= enterCosine && distance < nearestDistance) { nearest = source; nearestDistance = distance; }
    }
    public void finish() {
        if (nearest != null) active = nearest;
        else if (!available) active = null;
    }
    public boolean wanted(T source) { return source == active && (nearest != null || held); }
    public boolean mayLinger(T source) { return source == active && available && nearest == null; }
    /** Once exit begins, reacquiring a target requires the inner cone again. */
    public void release(T source) { if (source == active) active = null; }
}
