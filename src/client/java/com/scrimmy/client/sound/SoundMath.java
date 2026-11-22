package com.scrimmy.client.sound;

public final class SoundMath {
    private SoundMath() {}
    /** The camera forward vector is normalized; terrain never participates in selection. */
    public static boolean insideCone(double dx, double dy, double dz, double lx, double ly, double lz, double cosine) {
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance > .001 && (dx * lx + dy * ly + dz * lz) / distance >= cosine;
    }
    public static double distanceFade(double distance, double range, double fade) {
        return fade <= 0 ? 1 : Math.clamp((range - distance) / Math.min(fade, range), 0, 1);
    }
}
