package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.animation.Animation;
import com.scrimmy.client.animation.AnimationClock;
import com.scrimmy.client.theme.SurfaceStyle;
import com.scrimmy.client.theme.UiTokens;

/** One continuous appearance transition shared by panels, labels, and HUD glass. */
public final class ThemeSurfaces {
    private static final Animation GLASS = new Animation(1), TINT = new Animation(1);
    private static final Animation RED = new Animation(0x12), GREEN = new Animation(0x1b), BLUE = new Animation(0x27);
    private ThemeSurfaces() {}
    public static void frame() {
        var theme = ScrimmyClient.THEME;
        SurfaceStyle style = SurfaceStyle.get(theme.style.get());
        double dt = AnimationClock.UI.delta() * theme.animationSpeed.get();
        GLASS.spring(style.glass(), dt, UiTokens.STATE_FREQUENCY, 1);
        TINT.spring(style.tint(), dt, UiTokens.STATE_FREQUENCY, 1);
        RED.spring((style.background() >> 16) & 255, dt, UiTokens.STATE_FREQUENCY, 1);
        GREEN.spring((style.background() >> 8) & 255, dt, UiTokens.STATE_FREQUENCY, 1);
        BLUE.spring(style.background() & 255, dt, UiTokens.STATE_FREQUENCY, 1);
    }
    public static float glass() { return Math.clamp(GLASS.value(), 0, 1); }
    public static int fill() {
        var theme = ScrimmyClient.THEME;
        int solid = 0xff000000 | (Math.round(RED.value()) << 16) | (Math.round(GREEN.value()) << 8) | Math.round(BLUE.value());
        int glass = ((int)(theme.opacity.get() * 255) << 24) | (theme.glassTint.get() & 0xffffff);
        float f = Math.clamp(TINT.value(), 0, 1);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            result |= Math.round(((solid >>> shift) & 255) * (1 - f) + ((glass >>> shift) & 255) * f) << shift;
        return result;
    }
}
