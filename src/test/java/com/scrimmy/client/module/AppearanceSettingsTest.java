package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.ModuleSettings;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.theme.Theme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceSettingsTest {
    @Test void appearanceRoundTripsAndSpeedIsBounded() {
        Theme theme = new Theme();
        theme.style.set("Solid"); theme.animation.set("Cascade"); theme.font.set("Montserrat"); theme.animationSpeed.set(1.75);
        JsonObject saved = new JsonObject();
        theme.settings().forEach(s -> SettingCodec.write(saved, s));
        Theme restored = new Theme(); restored.settings().forEach(s -> SettingCodec.read(saved, s));
        assertEquals("Solid", restored.style.get()); assertEquals("Cascade", restored.animation.get());
        assertEquals("Montserrat", restored.font.get()); assertEquals(1.75, restored.animationSpeed.get());
        restored.animationSpeed.set(99.0); assertEquals(2, restored.animationSpeed.get());
        restored.animationSpeed.set(-99.0); assertEquals(.5, restored.animationSpeed.get());
    }
    @Test void legacyMotionMigrationPreservesExplicitNewSettingsAndMalformedValues() {
        JsonObject old = new JsonObject(); old.addProperty("easing", "Bouncy"); old.addProperty("opacity", .7);
        assertEquals("Spring", ModuleSettings.appearance(old).get("animation").getAsString());
        old.addProperty("animation", "Zoom");
        assertEquals("Zoom", ModuleSettings.appearance(old).get("animation").getAsString());
        old.remove("animation"); old.add("easing", new JsonObject());
        assertEquals("Smooth", ModuleSettings.appearance(old).get("animation").getAsString());
        assertEquals(.7, ModuleSettings.appearance(old).get("opacity").getAsDouble());
    }
    @Test void chunkFinderAliasPreservesTheEntireModuleAndCanonicalNameWins() {
        JsonObject modules = new JsonObject(), legacy = new JsonObject(), settings = new JsonObject();
        legacy.addProperty("enabled", true); legacy.addProperty("keybind", 67);
        settings.addProperty("cfDistance", 384); legacy.add("settings", settings);
        JsonObject extra = new JsonObject(); extra.addProperty("keep", 5); legacy.add("extra", extra);
        modules.add("Basefinding", legacy);
        JsonObject migrated = ModuleSettings.entry(modules, "ChunkFinder");
        assertSame(legacy, migrated); assertEquals(67, migrated.get("keybind").getAsInt());
        assertEquals(5, migrated.getAsJsonObject("extra").get("keep").getAsInt());
        assertEquals(24, ModuleSettings.migrate("ChunkFinder", settings).get("cfRange").getAsDouble());
        JsonObject canonical = new JsonObject(); canonical.addProperty("keybind", 68); modules.add("ChunkFinder", canonical);
        assertSame(canonical, ModuleSettings.entry(modules, "ChunkFinder"));
        assertEquals("Basefinding", Category.BASEFINDING.title());
        assertEquals("ChunkFinder", new com.scrimmy.client.module.basefinding.Basefinding().name());
    }
}
