package com.scrimmy.client.animation;

public enum Easing {
    OUT_CUBIC { public double apply(double t) { return 1 - Math.pow(1 - t, 3); } },
    OUT_QUINT { public double apply(double t) { return 1 - Math.pow(1 - t, 5); } },
    IN_OUT { public double apply(double t) { return t * t * t * (10 + t * (-15 + 6 * t)); } },
    OUT_BACK { public double apply(double t) { double u = t - 1; return 1 + 1.25 * u * u * u + .25 * u * u; } };
    public abstract double apply(double t);
    public double slope(double t) {
        return switch (this) {
            case OUT_CUBIC -> 3 * Math.pow(1 - t, 2);
            case OUT_QUINT -> 5 * Math.pow(1 - t, 4);
            case IN_OUT -> 30 * t * t * (t - 1) * (t - 1);
            case OUT_BACK -> 3.75 * (t - 1) * (t - 1) + .5 * (t - 1);
        };
    }
}
