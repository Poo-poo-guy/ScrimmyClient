package com.scrimmy.client.notification;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.animation.AnimationClock;
import com.scrimmy.client.animation.AnimationStyle;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.render.PanelBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Both existing hooks use this coordinator; style renderers share retained glass and layout behavior. */
public final class NotificationRenderer {
    private static final GlassToastRenderer[] CARDS = new GlassToastRenderer[ToastQueue.LIMIT];
    private static final GlassToastRenderer[] PILLS = new GlassToastRenderer[ToastQueue.LIMIT];
    private static final PanelBlur BLUR = new PanelBlur();
    private static long frame, drawnFrame = -1;
    private static ScreenRectangle viewport;
    static {
        for (int i = 0; i < ToastQueue.LIMIT; i++) { CARDS[i] = new CardToastRenderer(); PILLS[i] = new PillToastRenderer(); }
    }
    private NotificationRenderer() {}
    public static void beginFrame() { frame++; }
    private static GlassToastRenderer renderer(ToastQueue.Toast toast) { return (toast.style() == ToastStyle.PILL ? PILLS : CARDS)[toast.slot]; }
    /** Prepare glyphs only on ticks/content changes, preserving the allocation-free notification draw path. */
    public static void prepare() {
        ToastQueue queue = NotificationCenter.TOASTS;
        int revision = ClientFonts.revision();
        for (int i = 0; i < queue.size(); i++) {
            ToastQueue.Toast toast = queue.get(i);
            renderer(toast).prepare(toast, revision);
        }
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (viewport == null || viewport.width() != width || viewport.height() != height) viewport = new ScreenRectangle(0, 0, width, height);
    }
    public static void render(GuiGraphics g) {
        ToastQueue queue = NotificationCenter.TOASTS;
        NotificationSettings settings = NotificationCenter.settings();
        if (!settings.enabled.get()) { queue.clear(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (drawnFrame == frame || mc.getOverlay() != null || queue.size() == 0) return;
        drawnFrame = frame;
        var theme = ScrimmyClient.THEME;
        AnimationStyle style = AnimationStyle.get(theme.animation.get());
        double dt = AnimationClock.UI.delta() * theme.animationSpeed.get();
        queue.update(System.nanoTime() * 1e-9, AnimationClock.UI.delta(), theme.animations.get(), theme.animationSpeed.get(), style);
        if (queue.size() == 0 || viewport == null || viewport.width() != g.guiWidth() || viewport.height() != g.guiHeight()) return;
        BLUR.capture(mc, theme.blur.get().intValue());
        g.nextStratum();
        for (int i = 0; i < queue.size(); i++) {
            ToastQueue.Toast toast = queue.get(i);
            renderer(toast).render(g, toast, settings, style, dt, viewport, BLUR.texture());
        }
    }
}
