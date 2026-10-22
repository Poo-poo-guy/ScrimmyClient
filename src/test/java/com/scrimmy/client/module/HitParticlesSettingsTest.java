package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.visual.HitParticles;
import com.scrimmy.client.setting.ActionSetting;
import com.scrimmy.client.setting.GradientSetting;
import com.scrimmy.client.setting.Setting;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HitParticlesSettingsTest {
    private void action(HitParticles module, String key) {
        ((ActionSetting)module.settings().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()).run();
    }
    @Test void builtInPresetsBecomeCustomAndUserPresetsSurviveRoundTrip() {
        HitParticles module = new HitParticles();
        assertEquals(Category.VISUAL, module.category());
        assertEquals(5, Category.values().length);
        for (String preset : module.preset.options()) {
            module.preset.set(preset);
            assertEquals(preset, module.preset.get());
        }
        module.preset.set("Hearts"); assertEquals("Heart", module.shape.get());
        module.size.set(.4); assertEquals("Custom", module.preset.get());
        module.startColor.set(0x40776655);
        module.pool.set(List.of("Snowflake", "Ring"));
        module.gradient.set(List.of(new GradientSetting.Stop(0, 0x11ffffff), new GradientSetting.Stop(.3, 0x808855ee), new GradientSetting.Stop(1, 0xff000000)));
        action(module, "savePreset");
        HitParticles restored = new HitParticles(); restored.extraConfig(module.extraConfig());
        action(restored, "loadPreset");
        assertEquals(module.size.get(), restored.size.get());
        assertEquals(module.startColor.get(), restored.startColor.get());
        assertEquals(module.pool.get(), restored.pool.get());
        assertEquals(module.gradient.get(), restored.gradient.get());
        JsonObject values = new JsonObject();
        module.settings().forEach(s -> SettingCodec.write(values, s));
        assertFalse(values.has("savePreset"));
        restored.beginRestore(); restored.settings().forEach(s -> SettingCodec.read(values, s)); restored.endRestore();
        for (Setting<?> s : module.settings()) if (!(s instanceof ActionSetting))
            assertEquals(s.get(), restored.settings().stream().filter(r -> r.key().equals(s.key())).findFirst().orElseThrow().get());
    }
    @Test void badFieldsDoNotDiscardValidSettingsOrEmptyTheShapePool() {
        HitParticles module = new HitParticles();
        JsonObject json = new JsonObject(); json.addProperty("count", "bad"); json.addProperty("size", .35); json.addProperty("shape", "bad");
        module.beginRestore(); module.settings().forEach(s -> SettingCodec.read(json, s)); module.endRestore();
        assertEquals(14.0, module.count.get()); assertEquals(.35, module.size.get(), 1e-9); assertEquals("Star", module.shape.get());
        for (String option : List.copyOf(module.pool.get())) module.pool.toggle(option);
        assertFalse(module.pool.get().isEmpty());
    }
}
