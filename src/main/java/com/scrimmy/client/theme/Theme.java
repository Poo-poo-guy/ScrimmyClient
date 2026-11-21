package com.scrimmy.client.theme;

import com.scrimmy.client.setting.*;
import java.util.List;
import java.util.ArrayList;
import com.scrimmy.client.notification.NotificationSettings;
import com.scrimmy.client.animation.AnimationStyle;

public final class Theme {
    public final ColorSetting accent = new ColorSetting("accent", "Accent", 0xff70d6db);
    public final ColorSetting glassTint = new ColorSetting("glassTint", "Glass tint", 0xff152231);
    public final NumberSetting blur = new NumberSetting("blur", "Blur strength", 6, 0, 10, 1);
    public final NumberSetting opacity = new NumberSetting("opacity", "Panel opacity", 0.78, 0.3, 1, 0.01);
    public final NumberSetting radius = new NumberSetting("radius", "Corner radius", 8, 0, 26, 1);
    public final NumberSetting glow = new NumberSetting("glow", "Border glow", 0.5, 0, 1, 0.05);
    public final NumberSetting shadow = new NumberSetting("shadow", "Shadow intensity", 0.45, 0, 1, 0.05);
    public final NumberSetting animationSpeed = new NumberSetting("animationSpeed", "Animation Speed", 1, 0.5, 2, 0.05);
    public final ModeSetting style = new ModeSetting("style", "Style", "Glass", SurfaceStyle.names());
    public final ModeSetting animation = new ModeSetting("animation", "Animation", "Smooth", AnimationStyle.names());
    public final ModeSetting font = new ModeSetting("font", "Font", "Inter", FontCatalog.names());
    public final NumberSetting guiScale = new NumberSetting("guiScale", "ClickGUI scale", 1, 0.75, 1.5, 0.05);
    public final NumberSetting panelWidth = new NumberSetting("panelWidth", "Panel width", 200, 160, 280, 10);
    public final BooleanSetting animations = new BooleanSetting("animations", "Animations", true);

    public final NotificationSettings notifications = new NotificationSettings();
    private final List<Setting<?>> settings;
    public Theme() {
        var all = new ArrayList<Setting<?>>(List.of(style, animation, animationSpeed, font,
                accent, glassTint, blur, opacity, radius, glow, shadow, guiScale, panelWidth, animations));
        all.addAll(notifications.settings());
        settings = List.copyOf(all);
    }
    public List<Setting<?>> settings() { return settings; }
    public void onChange(Runnable callback) { settings.forEach(s -> s.onChange(callback)); }
    public void reset() { settings.forEach(Setting::reset); }
}
