package com.scrimmy.client.theme;

/** Positions are relative to the available viewport, so resizes preserve the layout. */
public record PanelState(double x, double y, boolean open) {
    public PanelState {
        x = safe(x);
        y = safe(y);
    }

    private static double safe(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
