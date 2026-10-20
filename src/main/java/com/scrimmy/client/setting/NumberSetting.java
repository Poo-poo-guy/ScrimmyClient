package com.scrimmy.client.setting;

public final class NumberSetting extends Setting<Double> {
    private final double min, max, step;
    public NumberSetting(String key, String label, double initial, double min, double max, double step) {
        super(key, label, initial);
        this.min = min; this.max = max; this.step = step;
    }
    public double min() { return min; }
    public double max() { return max; }
    public double step() { return step; }
    @Override protected Double sanitize(Double value) {
        if (value == null || !Double.isFinite(value)) return defaultValue();
        double clamped = Math.max(min, Math.min(max, value));
        return Math.max(min, Math.min(max, Math.round((clamped - min) / step) * step + min));
    }
}
