package com.scrimmy.client.trail;

/** Small allocation-free math shared by tunnel topology, visiting and rendering. */
public final class TrailMath {
    private TrailMath() {}
    // Minecraft's block-position layout; y is signed twelve-bit, x/z signed twenty-six-bit.
    public static long key(int x, int y, int z) {
        return ((long)x & 0x3ffffffL) << 38 | ((long)z & 0x3ffffffL) << 12 | (y & 0xfffL);
    }
    public static int x(long key) { return (int)(key >> 38); }
    public static int y(long key) { return (int)(key << 52 >> 52); }
    public static int z(long key) { return (int)(key << 26 >> 38); }
    public static long chunk(int x, int z) { return (x & 0xffffffffL) | (long)z << 32; }
    public static double spline(double a, double b, double c, double d, double t) {
        return .5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t
                + (-a + 3 * b - 3 * c + d) * t * t * t);
    }
    public static double distance(long a, long b) {
        return Math.sqrt(square(x(a) - x(b)) + square(y(a) - y(b)) + square(z(a) - z(b)));
    }
    private static double square(double v) { return v * v; }
    public static double bearing(double dx, double dz, double yaw) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
        return angle - Math.floor((angle + 180) / 360) * 360;
    }
    public static double fade(double distance, double range, double startFraction) {
        return Math.max(0, Math.min(1, (range - distance) / Math.max(1, range * (1 - startFraction))));
    }
}
