package com.scrimmy.client.util;

/** Screen input is already in Minecraft GUI units; apply only our additional scale. */
public final class GuiTransform {
    private float scale;
    private int minecraftScale;
    public GuiTransform(float scale, int minecraftScale) { update(scale, minecraftScale); }
    public float scale() { return scale; }
    public int minecraftScale() { return minecraftScale; }
    public void update(float scale, int minecraftScale) {
        if (!Float.isFinite(scale) || scale <= 0 || minecraftScale <= 0)
            throw new IllegalArgumentException("Invalid GUI scale");
        this.scale = scale; this.minecraftScale = minecraftScale;
    }
    public float local(double screenCoordinate) { return (float)(screenCoordinate / scale); }
    public float snap(float localCoordinate) {
        float physicalScale = scale * minecraftScale;
        return Math.round(localCoordinate * physicalScale) / physicalScale;
    }
    public Track track(float localX, float localWidth) { return new Track(localX * scale, localWidth * scale); }

    /** Freeze a slider's screen-space range while its own setting changes the layout. */
    public record Track(float x, float width) {
        public double fraction(double screenX) {
            return Math.max(0, Math.min(1, (screenX - x) / Math.max(1, width)));
        }
    }
}
