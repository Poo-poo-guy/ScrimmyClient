package com.scrimmy.client.notification;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.animation.Animation;
import com.scrimmy.client.animation.AnimationClock;
import com.scrimmy.client.animation.AnimationStyle;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.render.PanelBlur;
import com.scrimmy.client.render.RoundedRenderer;
import com.scrimmy.client.render.ThemeSurfaces;
import com.scrimmy.client.setting.GradientSetting;
import com.scrimmy.client.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** The HUD and screen hooks share this retained renderer, with one submission per GameRenderer frame. */
public final class NotificationRenderer {
    private static final View[] VIEWS = new View[ToastQueue.LIMIT];
    private static final PanelBlur BLUR = new PanelBlur();
    private static long frame, drawnFrame = -1;
    private static ScreenRectangle viewport;
    static { for (int i = 0; i < VIEWS.length; i++) VIEWS[i] = new View(); }
    private NotificationRenderer() {}
    public static void beginFrame() { frame++; }

    /** Layouts and glyph bindings are prepared on client ticks, never in the render path. */
    public static void prepare() {
        ToastQueue queue = NotificationCenter.TOASTS;
        int revision = ClientFonts.revision();
        for (int i = 0; i < queue.size(); i++) {
            ToastQueue.Toast toast = queue.get(i);
            View view = VIEWS[toast.slot];
            if (view.revision != revision) {
                view.on = ClientFonts.retain("Enabled", false);
                view.off = ClientFonts.retain("Disabled", false);
            }
            if (view.generation != toast.generation() || view.revision != revision) {
                view.name = ClientFonts.retain(toast.name(), true);
                // A newly admitted queued card begins its entrance only when its glyphs are ready.
                if (view.generation != toast.generation()) toast.reveal.snap(0);
                view.revision = revision; view.generation = toast.generation();
            }
        }
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (viewport == null || viewport.width() != width || viewport.height() != height) viewport = new ScreenRectangle(0, 0, width, height);
    }

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        ToastQueue queue = NotificationCenter.TOASTS;
        if (drawnFrame == frame || mc.getOverlay() != null || queue.size() == 0) return;
        drawnFrame = frame;
        var theme = ScrimmyClient.THEME;
        var settings = NotificationCenter.settings();
        AnimationStyle style = AnimationStyle.get(theme.animation.get());
        double dt = AnimationClock.UI.delta() * theme.animationSpeed.get();
        queue.update(System.nanoTime() * 1e-9, AnimationClock.UI.delta(), theme.animations.get(), theme.animationSpeed.get(), style);
        if (queue.size() == 0 || viewport == null || viewport.width() != g.guiWidth() || viewport.height() != g.guiHeight()) return;
        BLUR.capture(mc, theme.blur.get().intValue());
        g.nextStratum();
        boolean right = settings.position.get().endsWith("Right"), bottom = settings.position.get().startsWith("Bottom");
        int accent = theme.accent.get(), fill = ThemeSurfaces.fill();
        int radius = Math.min(15, theme.radius.get().intValue());
        for (int i = 0; i < queue.size(); i++) {
            ToastQueue.Toast toast = queue.get(i);
            View view = VIEWS[toast.slot];
            if (view.name == null || view.generation != toast.generation() || view.revision != ClientFonts.revision()) continue;
            float reveal = toast.reveal.value(), opacity = Math.clamp(reveal, 0, 1);
            if (opacity < .001f) continue;
            float desiredWidth = Math.min(Math.max(176, view.name.width() + 44), Math.max(32, Math.min(260, g.guiWidth() - 24)));
            float width = theme.animations.get() ? view.width.spring(desiredWidth, dt, UiTokens.STATE_FREQUENCY, 1) : desiredWidth;
            if (!theme.animations.get()) view.width.snap(width);
            float x = right ? g.guiWidth() - 12 - width / 2 : 12 + width / 2;
            x += (right ? 1 : -1) * (width + 20) * (1 - reveal);
            float y = bottom ? g.guiHeight() - 12 - 40 - toast.offset.value() : 12 + toast.offset.value();
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            float zoom = 1 - style.zoom() * (1 - opacity);
            g.pose().scale(zoom, zoom);
            int stateTint = GradientSetting.mix(UiTokens.MUTED, accent, Math.clamp(toast.state.value(), 0, 1));
            view.panel.draw(g, Math.round(width), 40, radius, alpha(fill, opacity),
                    alpha(accent, opacity * (.15f + .22f * theme.glow.get().floatValue())),
                    alpha(0x60000000, opacity * theme.shadow.get().floatValue()), viewport, BLUR.texture());
            g.pose().pushMatrix();
            g.pose().translate(-width / 2 + 15, 17);
            float pulse = 1 + .2f * toast.pulse.value();
            g.pose().scale(pulse, pulse);
            view.dot.draw(g, 6, 6, 3, alpha(stateTint, opacity), 0, 0, viewport);
            g.pose().popMatrix();
            float labelX = -width / 2 + 29;
            float textScale = Math.min(1, (width - 42) / Math.max(1, view.name.width()));
            g.pose().pushMatrix();
            g.pose().translate(labelX, 7);
            g.pose().scale(textScale, textScale);
            view.name.draw(g, 0, 0, alpha(UiTokens.TEXT, opacity), viewport);
            g.pose().popMatrix();
            float state = Math.clamp(toast.state.value(), 0, 1);
            view.on.draw(g, labelX, 22 + 3 * (1 - state), alpha(stateTint, opacity * state), viewport);
            view.off.draw(g, labelX, 22 - 3 * state, alpha(UiTokens.MUTED, opacity * (1 - state)), viewport);
            g.pose().popMatrix();
        }
    }
    private static int alpha(int color, float opacity) { return (Math.clamp(Math.round((color >>> 24) * opacity), 0, 255) << 24) | (color & 0xffffff); }
    private static final class View {
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel(), dot = new RoundedRenderer.ReusablePanel();
        final Animation width = new Animation(190);
        ClientFonts.RetainedLabel name, on, off;
        int revision = -1;
        long generation = -1;
    }
}
