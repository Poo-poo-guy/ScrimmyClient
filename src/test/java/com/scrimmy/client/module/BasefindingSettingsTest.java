package com.scrimmy.client.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.basefinding.Basefinding;
import com.scrimmy.client.setting.*;

import org.junit.jupiter.api.Test;

class BasefindingSettingsTest {
    @Test
    void everyChunkFinderSettingUsesTheProfileCodecIncludingAlphaAndGroups() {
        Basefinding module = new Basefinding(), restored = new Basefinding();
        assertEquals(Category.BASEFINDING, module.category());
        assertEquals("Basefinding", module.name());
        module.lowColor.set(0x12345678);
        module.singleMode.set(true);
        module.pulse.set(true);
        module.manMadeRadius.set(7.0);
        module.minimumConfidence.set("High");
        module.clusterWeight.set(42.0);
        JsonObject values = new JsonObject();
        module.settings().forEach(s -> SettingCodec.write(values, s));
        restored.settings().forEach(s -> SettingCodec.read(values, s));
        for (Setting<?> s : module.settings())
            if (!(s instanceof ActionSetting)) {
                Setting<?> next =
                        restored.settings().stream()
                                .filter(r -> r.key().equals(s.key()))
                                .findFirst()
                                .orElseThrow();
                if (s instanceof NumberSetting a && next instanceof NumberSetting b)
                    assertEquals(a.get(), b.get(), 1e-10, s.key());
                else assertEquals(s.get(), next.get(), s.key());
            }
        assertFalse(values.has("cfOpenLog"));
        assertTrue(module.finder.tooltip().contains("render distance"));
    }

    @Test
    void resetRestoresScoringAndColorsAndExclusionUsesChunkFootprint() {
        Basefinding module = new Basefinding();
        module.threshold.set(1.0);
        module.lowColor.set(0);
        module.spawnRadius.set(64.0);
        assertTrue(module.excluded(3, 0));
        assertTrue(module.excluded(-4, -1));
        assertFalse(module.excluded(4, 0));
        ((ActionSetting)
                        module.settings().stream()
                                .filter(s -> s.key().equals("cfReset"))
                                .findFirst()
                                .orElseThrow())
                .run();
        assertEquals(60, module.threshold.get());
        assertEquals(0xcc70b8de, module.lowColor.get());
        assertFalse(module.excluded(0, 0));
    }
}
