package com.scrimmy.client.ghost;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Small, allocation-free calculations shared by world markers and their HUD projection. */
public final class GhostMath {
    public static final double FADE_FRACTION = .1, MAX_FADE_SECONDS = 10, DISTANCE_DIMMING = .4;
    public static final int OLD_COLOR = 0x7E8B94;
    private static final float MIN_CLIP_W = .01f;
    private GhostMath() {}
    public static double fade(double age, double duration, double distance, double fadeDistance, double maxDistance) {
        if (age >= duration || distance > maxDistance) return 0;
        double window = Math.min(duration * FADE_FRACTION, MAX_FADE_SECONDS);
        double remaining = smooth((duration - age) / window);
        double far = maxDistance > fadeDistance ? smooth((distance - fadeDistance) / (maxDistance - fadeDistance)) : 0;
        return remaining * (1 - DISTANCE_DIMMING * far);
    }
    public static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }
    public static int color(int base, double ageFraction, double opacity) {
        double t = smooth(ageFraction);
        int red = channel(base >> 16, OLD_COLOR >> 16, t), green = channel(base >> 8, OLD_COLOR >> 8, t), blue = channel(base, OLD_COLOR, t);
        int alpha = (int)((base >>> 24) * Math.max(0, Math.min(1, opacity)));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
    private static int channel(int a, int b, double t) { return (int)((a & 255) + ((b & 255) - (a & 255)) * t); }
    public static boolean project(Matrix4fc matrix, float x, float y, float z, Vector4f clip) {
        matrix.transform(clip.set(x, y, z, 1));
        // Perspective w is negative behind the camera. Never divide it before checking.
        return clip.w > MIN_CLIP_W && Math.abs(clip.x) <= clip.w && Math.abs(clip.y) <= clip.w && Math.abs(clip.z) <= clip.w;
    }
}
