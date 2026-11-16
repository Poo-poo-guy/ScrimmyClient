package com.scrimmy.client.module;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.scrimmy.client.config.*;
import com.scrimmy.client.module.basefinding.Basefinding;
import org.junit.jupiter.api.Test;
import java.util.List;

class BasefindingSettingsTest {
    @Test void onlyRequestedControlsRemainAndLegacyValuesMigrate() {
        Basefinding module = new Basefinding();
        assertEquals(List.of("cfShowGeodes", "cfHighlightChunks", "cfLabels", "cfThreshold", "cfRange"),
                module.settings().stream().map(s -> s.key()).toList());
        JsonObject old = new JsonObject();
        old.addProperty("cfThreshold", 87); old.addProperty("cfDistance", 384);
        old.addProperty("cfLabels", false); old.addProperty("cfBudget", "broken");
        old.addProperty("cfFill", false); old.addProperty("cfOutline", false);
        JsonObject migrated = ModuleSettings.migrate(module.name(), old);
        module.settings().forEach(s -> SettingCodec.read(migrated, s));
        assertEquals(87, module.threshold.get()); assertEquals(24, module.range.get());
        assertFalse(module.nametags.get()); assertFalse(module.highlightChunks.get());
        JsonObject saved = new JsonObject(); module.settings().forEach(s -> SettingCodec.write(saved, s));
        assertFalse(saved.has("cfBudget")); assertFalse(saved.has("cfDistance"));
    }
    @Test void newKeysWinAndMalformedOldFieldsKeepDefaults() {
        Basefinding module = new Basefinding();
        JsonObject old = new JsonObject(); old.addProperty("cfDistance", "bad");
        old.addProperty("cfRange", 8); old.addProperty("cfThreshold", "bad");
        var values = ModuleSettings.migrate(module.name(), old);
        module.settings().forEach(s -> SettingCodec.read(values, s));
        assertEquals(8, module.range.get()); assertEquals(60, module.threshold.get());
        assertTrue(module.showGeodes.get());
    }
}
