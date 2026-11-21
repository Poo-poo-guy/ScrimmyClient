package com.scrimmy.client.notification;

import com.scrimmy.client.setting.BooleanSetting;
import com.scrimmy.client.setting.GroupSetting;
import com.scrimmy.client.setting.ModeSetting;
import com.scrimmy.client.setting.NumberSetting;
import com.scrimmy.client.setting.Setting;
import java.util.List;

/** Part of ScrimmyClient+'s theme settings, not a separately toggleable module. */
public final class NotificationSettings {
    public final GroupSetting section = new GroupSetting("notificationsSection", "Notifications", false);
    public final BooleanSetting enabled = new BooleanSetting("notificationsEnabled", "Notifications", true);
    public final ModeSetting style = new ModeSetting("notificationsStyle", "Style", "Card", "Card", "Pill");
    public final ModeSetting position = new ModeSetting("notificationsPosition", "Position", "Top Right",
            "Top Right", "Bottom Right", "Top Left", "Bottom Left");
    public final NumberSetting duration = new NumberSetting("notificationsDuration", "Duration (s)", 2.5, 1, 6, .25);
    public final ModeSetting source = new ModeSetting("notificationsSource", "Source", "Keybind only", "Keybind only", "All toggles");
    public final NumberSetting opacity = new NumberSetting("notificationsOpacity", "Opacity", .78, .1, 1, .01);
    public final NumberSetting cornerRadius = new NumberSetting("notificationsCornerRadius", "Corner Radius", -1, -1, 26, 1);
    public final BooleanSetting accentTint = new BooleanSetting("notificationsAccentTint", "Accent Tint", true);
    public final BooleanSetting sound = new BooleanSetting("notificationsSound", "Sound", true);
    public final NumberSetting volume = new NumberSetting("notificationsVolume", "Volume", .25, 0, 1, .05);
    public final BooleanSetting pitchVariation = new BooleanSetting("notificationsPitchVariation", "Pitch Variation", true);
    private final List<Setting<?>> settings = List.of(section, enabled, style, position, duration, source, opacity, cornerRadius, accentTint,
            sound, volume, pitchVariation);

    public NotificationSettings() {
        section.tooltip("Expand notification settings inside ScrimmyClient+.");
        enabled.tooltip("Enable or disable all module toggle notifications.");
        style.tooltip("Card uses two lines; Pill is a compact capsule with ON/OFF at the right.");
        position.tooltip("Choose the screen corner where notifications stack.");
        duration.tooltip("How long a notification holds before fading away.");
        source.tooltip("Show keybind changes only, or include ClickGUI and other module changes.");
        opacity.tooltip("Background opacity in glass mode; solid mode remains opaque.");
        cornerRadius.tooltip("Auto (-1): global radius for Card, fully rounded Pill. Values 0–26 override both.");
        accentTint.tooltip("Use the accent color for enabled state text and a subtle glass tint.");
        sound.tooltip("Play a UI click for a new notification or visible enabled/disabled change.");
        volume.tooltip("Notification sound volume, multiplied by Minecraft's master and UI volumes.");
        pitchVariation.tooltip("Slightly vary each click's pitch; disabled clicks always sound lower.");
        for (int i = 1; i < settings.size(); i++) settings.get(i).group(section);
    }
    public List<Setting<?>> settings() { return settings; }
    public ToastStyle selectedStyle() { return style.get().equals("Pill") ? ToastStyle.PILL : ToastStyle.CARD; }
    public int radius(ToastStyle style, int globalRadius) {
        int radius = cornerRadius.get().intValue();
        if (radius < 0) radius = style == ToastStyle.PILL ? style.height() / 2 : Math.min(15, globalRadius);
        return Math.clamp(radius, 0, style.height() / 2);
    }
}
