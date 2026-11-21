package com.scrimmy.client.particle;

public final class ParticleMath {
    private ParticleMath() {}
    public static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    public static double ease(String curve, double value) {
        double t = clamp(value);
        return switch (curve) {
            case "Ease-out" -> 1 - Math.pow(1 - t, 3);
            case "Ease-in-out" -> t * t * (3 - 2 * t);
            case "Back" -> 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
            case "Elastic" -> t == 0 || t == 1 ? t : Math.pow(2, -10 * t) * Math.sin((t * 10 - .75) * 2 * Math.PI / 3) + 1;
            default -> t;
        };
    }
    public static double scale(String curve, double age, double lifetime, double pop, double endSize) {
        // Pop-in is continuous even for overshooting easing curves; hold until the last 45%.
        double appear = pop <= 0 ? 1 : Math.max(0, ease(curve, age / pop));
        double disappear = ease(curve, (age / lifetime - .55) / .45);
        return Math.max(0, appear * (1 + (endSize - 1) * disappear));
    }
    public static double alpha(String curve, double age, double lifetime, double pop, double start, double end) {
        double t = clamp(ease(curve, age / lifetime));
        // Force both endpoints to zero, even if the selected end alpha is nonzero.
        double envelope = clamp(age / Math.max(.001, pop)) * clamp((lifetime - age) / Math.min(.12, lifetime * .2));
        return clamp((start + (end - start) * t) * envelope);
    }
}
