package com.scrimmy.client.notification;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** One-line capsule with a stable right-aligned state column and reversible vertical crossfade. */
final class PillToastRenderer extends GlassToastRenderer {
    PillToastRenderer() { super(ToastStyle.PILL, "ON", "OFF"); }
    @Override protected float desiredWidth() { return Math.max(104, name.width() + Math.max(on.width(), off.width()) + 42); }
    @Override protected void content(GuiGraphics g, ToastQueue.Toast toast, float width, float opacity,
                                     float state, int color, ScreenRectangle viewport) {
        float reserve = Math.max(on.width(), off.width());
        name(g, -width / 2 + 12, 9, width - reserve - 42, opacity, viewport);
        float right = width / 2 - 12;
        state(g, right - on.width(), right - off.width(), 9, opacity, state, color, true, viewport);
    }
}
