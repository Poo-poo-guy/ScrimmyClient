package com.scrimmy.client.util;

/** Screen input is already in Minecraft GUI units; apply only our additional scale. */
public record GuiTransform(float scale, int minecraftScale) {
    public GuiTransform {
        if (!Float.isFinite(scale) || scale <= 0 || minecraftScale <= 0)
            throw new IllegalArgumentException("Invalid GUI scale");
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
