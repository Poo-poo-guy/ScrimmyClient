package com.scrimmy.client.notification;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.animation.Animation;
import com.scrimmy.client.animation.AnimationStyle;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.render.RoundedRenderer;
import com.scrimmy.client.render.ThemeSurfaces;
import com.scrimmy.client.setting.GradientSetting;
import com.scrimmy.client.theme.UiTokens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;

/** Retained common glass, placement, sizing and state transitions; subclasses supply only content layout. */
abstract class GlassToastRenderer {
    private final ToastStyle style;
    private final String onText, offText;
    private final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
    private final Animation width, backgroundOpacity = new Animation(.78), radius, tint = new Animation(1);
    protected ClientFonts.RetainedLabel name, on, off;
    private int revision = -1;
    private long generation = -1;
    private String preparedName;

    GlassToastRenderer(ToastStyle style, String onText, String offText) {
        this.style = style; this.onText = onText; this.offText = offText;
        width = new Animation(style == ToastStyle.CARD ? 190 : 132);
        radius = new Animation(style == ToastStyle.CARD ? 8 : style.height() / 2);
    }
    final void prepare(ToastQueue.Toast toast, int fontRevision) {
        if (revision != fontRevision) {
            on = ClientFonts.retain(onText, false); off = ClientFonts.retain(offText, false);
        }
        if (revision != fontRevision || !toast.name().equals(preparedName)) {
            name = ClientFonts.retain(toast.name(), true); preparedName = toast.name();
        }
        // Queued cards wait for prepared glyphs before starting their visible entrance.
        if (generation != toast.generation()) toast.reveal.snap(0);
        revision = fontRevision; generation = toast.generation();
    }
    final void render(GuiGraphics g, ToastQueue.Toast toast, NotificationSettings settings, AnimationStyle motion,
                      double dt, ScreenRectangle viewport, TextureSetup texture) {
        if (name == null || revision != ClientFonts.revision() || generation != toast.generation()) return;
        float reveal = toast.reveal.value(), opacity = Math.clamp(reveal, 0, 1);
        if (opacity < .001f) return;
        var theme = ScrimmyClient.THEME;
        boolean animated = theme.animations.get();
        float available = Math.max(32, Math.min(260, g.guiWidth() - 24));
        float w = move(width, Math.min(desiredWidth(), available), dt, animated);
        float glass = ThemeSurfaces.glass();
        float background = move(backgroundOpacity, settings.opacity.get(), dt, animated);
        float corner = move(radius, settings.radius(style, theme.radius.get().intValue()), dt, animated);
        float accentMix = move(tint, settings.accentTint.get() ? 1 : 0, dt, animated);
        float state = Math.clamp(toast.state.value(), 0, 1);
        int accent = theme.accent.get();
        int stateColor = GradientSetting.mix(UiTokens.MUTED, GradientSetting.mix(UiTokens.TEXT, accent, accentMix), state);
        int fill = ThemeSurfaces.fill();
        int tinted = GradientSetting.mix(fill, accent, .08f * state * accentMix * glass);
        fill = (Math.clamp(Math.round(255 * (1 - glass + glass * background)), 0, 255) << 24) | (tinted & 0xffffff);
        int border = GradientSetting.mix(UiTokens.BORDER, accent, accentMix);
        boolean right = settings.position.get().endsWith("Right"), bottom = settings.position.get().startsWith("Bottom");
        float x = right ? g.guiWidth() - 12 - w / 2 : 12 + w / 2;
        x += (right ? 1 : -1) * (w + 20) * (1 - reveal);
        float y = bottom ? g.guiHeight() - 12 - style.height() - toast.offset.value() : 12 + toast.offset.value();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        float zoom = 1 - motion.zoom() * (1 - opacity);
        g.pose().scale(zoom, zoom);
        panel.draw(g, Math.round(w), style.height(), Math.round(corner), alpha(fill, opacity),
                alpha(border, opacity * (.15f + .22f * theme.glow.get().floatValue())),
                alpha(0x60000000, opacity * theme.shadow.get().floatValue()), viewport, texture);
        content(g, toast, w, opacity, state, stateColor, viewport);
        g.pose().popMatrix();
    }
    protected abstract float desiredWidth();
    protected abstract void content(GuiGraphics g, ToastQueue.Toast toast, float width, float opacity,
                                    float state, int stateColor, ScreenRectangle viewport);
    protected final void name(GuiGraphics g, float x, float y, float room, float opacity, ScreenRectangle viewport) {
        float scale = Math.min(1, Math.max(1, room) / Math.max(1, name.width()));
        g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(scale, scale);
        name.draw(g, 0, 0, alpha(UiTokens.TEXT, opacity), viewport);
        g.pose().popMatrix();
    }
    protected final void state(GuiGraphics g, float onX, float offX, float y, float opacity, float state,
                               int color, boolean tintOff, ScreenRectangle viewport) {
        on.draw(g, onX, y + 3 * (1 - state), alpha(color, opacity * state), viewport);
        off.draw(g, offX, y - 3 * state, alpha(tintOff ? color : UiTokens.MUTED, opacity * (1 - state)), viewport);
    }
    private static float move(Animation animation, double target, double dt, boolean enabled) {
        if (!enabled) { animation.snap(target); return animation.value(); }
        return animation.spring(target, dt, UiTokens.STATE_FREQUENCY, 1);
    }
    protected static int alpha(int color, float opacity) { return (Math.clamp(Math.round((color >>> 24) * opacity), 0, 255) << 24) | (color & 0xffffff); }
}
