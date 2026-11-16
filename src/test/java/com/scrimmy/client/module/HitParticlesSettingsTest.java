package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.*;
import com.scrimmy.client.module.visual.HitParticles;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HitParticlesSettingsTest {
    @Test void presetsRetainTheirBehaviorAfterSavingAdjustedControls() {
        for (String preset : new HitParticles().preset.options()) {
            HitParticles module = new HitParticles(); module.preset.set(preset); module.size.set(.4);
            JsonObject values = new JsonObject(); module.settings().forEach(s -> SettingCodec.write(values, s));
            HitParticles restored = new HitParticles(); restored.beginRestore();
            restored.settings().forEach(s -> SettingCodec.read(values, s)); restored.endRestore();
            assertEquals(module.shape, restored.shape, preset); assertEquals(module.motion, restored.motion, preset);
            assertEquals(module.trail, restored.trail, preset); assertEquals(module.size.get(), restored.size.get(), preset);
            assertEquals(preset, restored.preset.get());
        }
    }
    @Test void removedFieldsAreIgnoredAndLegacyCustomStyleIsMapped() {
        HitParticles module = new HitParticles();
        JsonObject json = new JsonObject(); json.addProperty("count", "bad"); json.addProperty("size", .35);
        json.addProperty("preset", "Custom"); json.addProperty("shape", "Heart"); json.addProperty("gravity", "bad");
        var values = ModuleSettings.migrate(module.name(), json);
        module.beginRestore(); module.settings().forEach(s -> SettingCodec.read(values, s)); module.endRestore();
        assertEquals(14, module.count.get()); assertEquals(.35, module.size.get(), 1e-9);
        assertEquals("Hearts", module.preset.get()); assertEquals("Heart", module.shape);
        assertEquals(6, module.settings().size());
    }
}
