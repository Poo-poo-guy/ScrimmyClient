package com.scrimmy.client.render;

import net.minecraft.client.gui.GuiGraphics;

/** CPU coverage fallback for the 1.21.11 GUI render state, with no custom shader dependency. */
public final class RoundedRenderer {
    private RoundedRenderer() {}

    public static void panel(GuiGraphics g, int x, int y, int width, int height, int radius,
                             int fill, int border, float glow, float shadow) {
        int r = Math.min(radius, Math.min(width, height) / 2);
        for (int spread = 4; spread >= 1; spread--) {
            int alpha = (int)(shadow * (5 - spread) * 5);
            rounded(g, x - spread, y - spread + 2, width + spread * 2, height + spread * 2, r + spread,
                (alpha << 24));
        }
        rounded(g, x, y, width, height, r, border);
        rounded(g, x + 1, y + 1, width - 2, height - 2, Math.max(0, r - 1), fill);
        if (glow > 0) {
            int alpha = (int)(Math.min(1, glow) * 54);
            rounded(g, x + 2, y + 2, width - 4, height - 4, Math.max(0, r - 2), (alpha << 24) | (border & 0xffffff));
            rounded(g, x + 3, y + 3, width - 6, height - 6, Math.max(0, r - 3), fill);
        }
    }

    public static void rounded(GuiGraphics g, int x, int y, int width, int height, int radius, int color) {
        rounded(g, x, y, width, height, radius, radius, radius, radius, color);
    }

    public static void rounded(GuiGraphics g, int x, int y, int width, int height,
                               int topLeft, int topRight, int bottomRight, int bottomLeft, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        int limit = Math.min(width, height) / 2;
        int tl = Math.max(0, Math.min(limit, topLeft)), tr = Math.max(0, Math.min(limit, topRight));
        int br = Math.max(0, Math.min(limit, bottomRight)), bl = Math.max(0, Math.min(limit, bottomLeft));
        int top = Math.max(tl, tr), bottom = Math.max(bl, br);
        if (height > top + bottom) g.fill(x, y + top, x + width, y + height - bottom, color);
        for (int row = 0; row < top; row++) {
            cornerRow(g, x, y + row, width, tl, tr, row, color);
        }
        for (int row = 0; row < bottom; row++) {
            cornerRow(g, x, y + height - 1 - row, width, bl, br, row, color);
        }
    }

    private static void cornerRow(GuiGraphics g, int x, int y, int width, int leftRadius, int rightRadius, int row, int color) {
        double leftEdge = edge(leftRadius, row), rightEdge = edge(rightRadius, row);
        int leftSolid = (int)Math.ceil(leftEdge), rightSolid = (int)Math.ceil(rightEdge);
        if (width > leftSolid + rightSolid)
            g.fill(x + leftSolid, y, x + width - rightSolid, y + 1, color);
        if (leftSolid > 0) fringe(g, x + leftSolid - 1, y, leftSolid - leftEdge, color);
        if (rightSolid > 0) fringe(g, x + width - rightSolid, y, rightSolid - rightEdge, color);
    }

    private static double edge(int radius, int row) {
        if (radius == 0 || row >= radius) return 0;
        double vertical = radius - row - 0.5;
        return radius - Math.sqrt(Math.max(0, radius * radius - vertical * vertical));
    }

    private static void fringe(GuiGraphics g, int x, int y, double coverage, int color) {
        int alpha = (int)((color >>> 24) * Math.max(0, Math.min(1, coverage)));
        if (alpha > 0) {
            g.fill(x, y, x + 1, y + 1, (alpha << 24) | (color & 0xffffff));
        }
    }
}
