package com.scrimmy.client.notification;

import com.scrimmy.client.render.RoundedRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Original two-line card layout. Entrance, glass, state easing and sizing live in the base. */
final class CardToastRenderer extends GlassToastRenderer {
    private final RoundedRenderer.ReusablePanel dot = new RoundedRenderer.ReusablePanel();
    CardToastRenderer() { super(ToastStyle.CARD, "Enabled", "Disabled"); }
    @Override protected float desiredWidth() { return Math.max(176, name.width() + 44); }
    @Override protected void content(GuiGraphics g, ToastQueue.Toast toast, float width, float opacity,
                                     float state, int color, ScreenRectangle viewport) {
        g.pose().pushMatrix(); g.pose().translate(-width / 2 + 15, 17);
        float pulse = 1 + .2f * toast.pulse.value(); g.pose().scale(pulse, pulse);
        dot.draw(g, 6, 6, 3, alpha(color, opacity), 0, 0, viewport);
        g.pose().popMatrix();
        float x = -width / 2 + 29;
        name(g, x, 7, width - 42, opacity, viewport);
        state(g, x, x, 22, opacity, state, color, false, viewport);
    }
}
