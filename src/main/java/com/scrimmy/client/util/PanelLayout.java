package com.scrimmy.client.util;

/** An automatic layout reserves a full cell, even while its panel is collapsed. */
public record PanelLayout(int width, int columns, int cellHeight, int gap) {
    public static PanelLayout fit(int viewportWidth, int viewportHeight, int preferredWidth, int count, int gap) {
        int columns = Math.min(count, Math.max(1, (viewportWidth - 8 + gap) / (Math.min(160, preferredWidth) + gap)));
        int maxRows = Math.max(1, (viewportHeight - 8 + gap) / (30 + gap));
        columns = Math.max(columns, (count + maxRows - 1) / maxRows);
        int rows = (count + columns - 1) / columns;
        int width = Math.max(24, Math.min(preferredWidth, (viewportWidth - 8 - gap * (columns - 1)) / columns));
        int height = Math.max(30, (viewportHeight - 8 - gap * (rows - 1)) / rows);
        return new PanelLayout(width, columns, height, gap);
    }
    public int x(int index) { return 4 + (index % columns) * (width + gap); }
    public int y(int index) { return 4 + (index / columns) * (cellHeight + gap); }
}
