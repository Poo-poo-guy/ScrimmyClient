package com.scrimmy.client.camera;

/** Camera-only coordinates: never written to the player or sent to the server. */
public final class FreecamMotion {
    public static final double DEFAULT_SPEED = 8, MIN_SPEED = .1, MAX_SPEED = 128, SPEED_STEP = .1;
    private static final double SCROLL_FACTOR = 1.25, MAX_SCROLL_STEPS = 64;
    private static final float MOUSE_SCALE = .15f, MAX_PITCH = 90;
    public double x, y, z;
    public float yaw, pitch;
    private double oldX, oldY, oldZ, wheelRemainder;

    public void reset(double x, double y, double z, float yaw, float pitch) {
        this.x = oldX = x; this.y = oldY = y; this.z = oldZ = z;
        this.yaw = yaw; this.pitch = pitch; wheelRemainder = 0;
    }
    public void beginTick() { oldX = x; oldY = y; oldZ = z; }
    public void turn(double horizontal, double vertical) {
        yaw = (float)((yaw + horizontal * MOUSE_SCALE) % 360);
        pitch = (float)Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + vertical * MOUSE_SCALE));
    }
    public void move(int forward, int right, int up, double distance) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
        double sin = Math.sin(yawRadians), cos = Math.cos(yawRadians), horizontal = Math.cos(pitchRadians);
        double dx = -sin * horizontal * forward + cos * right;
        double dy = -Math.sin(pitchRadians) * forward + up;
        double dz = cos * horizontal * forward + sin * right;
        double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (length > 1) { dx /= length; dy /= length; dz /= length; }
        x += dx * distance; y += dy * distance; z += dz * distance;
    }
    public double renderX(float partial) { return lerp(oldX, x, partial); }
    public double renderY(float partial) { return lerp(oldY, y, partial); }
    public double renderZ(float partial) { return lerp(oldZ, z, partial); }
    private static double lerp(double previous, double current, float partial) {
        return previous + (current - previous) * Math.max(0, Math.min(1, partial));
    }
    public int scrollSteps(double delta) {
        if (!Double.isFinite(delta)) return 0;
        wheelRemainder = Math.max(-MAX_SCROLL_STEPS, Math.min(MAX_SCROLL_STEPS, wheelRemainder + delta));
        int steps = (int)wheelRemainder;
        wheelRemainder -= steps;
        return steps;
    }
    public static double scrollSpeed(double current, int steps) {
        if (steps == 0) return current;
        double target = current * Math.pow(SCROLL_FACTOR, steps);
        // Every notch must move at least one setting step, including at the minimum speed.
        target = steps > 0 ? Math.max(current + SPEED_STEP, target) : Math.min(current - SPEED_STEP, target);
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, target));
    }
}
