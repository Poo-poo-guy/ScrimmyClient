package com.scrimmy.client.notification;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.ModuleSettings;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.theme.Theme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingsTest {
    @Test void legacyEnabledPositionDurationAndSourceMigrateToTheAppearanceSection() {
        JsonObject legacy = new JsonObject(), controls = new JsonObject(), modules = new JsonObject(), appearance = new JsonObject();
        legacy.addProperty("enabled", false);
        controls.addProperty("position", "Bottom Left"); controls.addProperty("duration", 4.75); controls.addProperty("source", "All toggles");
        legacy.add("settings", controls); modules.add("Notifications", legacy); appearance.addProperty("opacity", .55);
        JsonObject migrated = ModuleSettings.appearance(appearance, modules);
        Theme theme = new Theme(); theme.settings().forEach(s -> SettingCodec.read(migrated, s));
        var settings = theme.notifications;
        assertFalse(settings.enabled.get()); assertEquals("Bottom Left", settings.position.get());
        assertEquals(4.75, settings.duration.get()); assertEquals("All toggles", settings.source.get());
        assertEquals(.55, settings.opacity.get()); assertEquals("Card", settings.style.get());
        assertFalse(appearance.has("notificationsEnabled")); assertEquals(1, modules.size());
        JsonObject saved = new JsonObject(); theme.settings().forEach(s -> SettingCodec.write(saved, s));
        assertFalse(saved.get("notificationsEnabled").getAsBoolean());
        assertFalse(saved.has("enabled")); assertFalse(saved.has("Notifications"));
        Theme restored = new Theme(); restored.settings().forEach(s -> SettingCodec.read(saved, s));
        for (int i = 0; i < settings.settings().size(); i++)
            assertEquals(settings.settings().get(i).get(), restored.notifications.settings().get(i).get());
    }
    @Test void canonicalValuesWinIndependentlyAndMalformedLegacyControlsDoNotLoseValidOnes() {
        JsonObject modules = new JsonObject(), legacy = new JsonObject(), old = new JsonObject(), canonical = new JsonObject();
        legacy.addProperty("enabled", false); old.addProperty("position", "Bottom Right");
        old.addProperty("duration", "invalid"); old.addProperty("source", "All toggles");
        legacy.add("settings", old); modules.add("Notifications", legacy);
        canonical.addProperty("notificationsEnabled", true); canonical.addProperty("notificationsPosition", "Top Left");
        canonical.addProperty("notificationsStyle", "Pill"); canonical.addProperty("notificationsOpacity", .9);
        canonical.addProperty("notificationsCornerRadius", 7); canonical.addProperty("notificationsAccentTint", false);
        JsonObject migrated = ModuleSettings.appearance(canonical, modules);
        Theme theme = new Theme(); theme.settings().forEach(s -> SettingCodec.read(migrated, s));
        var settings = theme.notifications;
        assertTrue(settings.enabled.get()); assertEquals("Top Left", settings.position.get());
        assertEquals(2.5, settings.duration.get()); assertEquals("All toggles", settings.source.get());
        assertEquals(ToastStyle.PILL, settings.selectedStyle()); assertEquals(.9, settings.opacity.get());
        assertEquals(7, settings.radius(ToastStyle.PILL, 8)); assertFalse(settings.accentTint.get());
        assertEquals(migrated, ModuleSettings.appearance(migrated, modules));
    }
    @Test void autoRadiusPreservesCardCornersAndMakesPillsFullyRounded() {
        NotificationSettings settings = new NotificationSettings();
        assertEquals(8, settings.radius(ToastStyle.CARD, 8));
        assertEquals(13, settings.radius(ToastStyle.PILL, 8));
        settings.cornerRadius.set(0.0);
        assertEquals(0, settings.radius(ToastStyle.CARD, 8)); assertEquals(0, settings.radius(ToastStyle.PILL, 8));
        settings.cornerRadius.set(26.0);
        assertEquals(20, settings.radius(ToastStyle.CARD, 8)); assertEquals(13, settings.radius(ToastStyle.PILL, 8));
        settings.opacity.set(0.0); assertEquals(.1, settings.opacity.get());
        settings.opacity.set(2.0); assertEquals(1, settings.opacity.get());
    }
    @Test void missingLegacyModuleKeepsDefaultsAndEveryControlIsInsideTheCollapsibleGroup() {
        Theme theme = new Theme();
        JsonObject migrated = ModuleSettings.appearance(new JsonObject(), new JsonObject());
        theme.settings().forEach(s -> SettingCodec.read(migrated, s));
        assertTrue(theme.notifications.enabled.get()); assertFalse(theme.notifications.section.get());
        assertEquals("Keybind only", theme.notifications.source.get());
        for (var setting : theme.notifications.settings()) {
            assertTrue(theme.settings().contains(setting));
            if (setting != theme.notifications.section) assertSame(theme.notifications.section, setting.group());
        }
    }
}
