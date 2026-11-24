package com.scrimmy.client.module;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scrimmy.client.config.*;
import com.scrimmy.client.module.visual.BlockESPModule;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.module.basefinding.ActivityLog;
import org.junit.jupiter.api.Test;
import java.util.List;

class ModuleSettingsTest {
    @Test void everyModuleHasASmallSetOfDocumentedControlsThatRoundTrips() {
        ModuleManager source = new ModuleManager(), target = new ModuleManager();
        for (int i = 0; i < source.all().size(); i++) {
            Module module = source.all().get(i), restored = target.all().get(i);
            // Detection logs explicitly expose grouped capture, retention and rendering controls.
            if (!(module instanceof SoundFinder) && !(module instanceof ActivityLog)) assertTrue(module.settings().size() <= 6, module.name());
            JsonObject values = new JsonObject();
            module.settings().forEach(s -> {
                assertNotEquals(s.label(), s.tooltip(), module.name() + ": " + s.label());
                SettingCodec.write(values, s);
            });
            values.addProperty("removedInternalTuning", "broken");
            restored.beginRestore(); restored.settings().forEach(s -> SettingCodec.read(values, s)); restored.endRestore();
            for (int j = 0; j < module.settings().size(); j++)
                assertEquals(module.settings().get(j).get(), restored.settings().get(j).get(), module.name());
        }
    }
    @Test void legacyBlockSwitchesMergeIncludingAnExplicitlyEmptySelection() {
        BlockESPModule module = new BlockESPModule();
        JsonObject old = new JsonObject();
        for (int i = 0; i < 7; i++) old.addProperty("group" + i, false);
        JsonObject values = ModuleSettings.migrate(module.name(), old);
        for (var s : module.settings()) SettingCodec.read(values, s);
        assertEquals(List.of(), module.groups.get());
        old.addProperty("group1", true); old.addProperty("group4", true);
        values = ModuleSettings.migrate(module.name(), old);
        SettingCodec.read(values, module.groups);
        assertEquals(List.of("Ores", "Portals"), module.groups.get());
    }
    @Test void currentMergedSelectionWinsOverOldSwitchesAndModuleEnableIsSafe() {
        JsonObject old = new JsonObject(); old.addProperty("group0", true);
        JsonArray selected = new JsonArray(); selected.add("Beds"); old.add("blockGroups", selected);
        BlockESPModule module = new BlockESPModule();
        SettingCodec.read(ModuleSettings.migrate(module.name(), old), module.groups);
        assertEquals(List.of("Beds"), module.groups.get());
        assertFalse(ModuleSettings.bool(new JsonObject(), "cfEnabled", false));
        old.add("cfEnabled", new JsonObject()); assertTrue(ModuleSettings.bool(old, "cfEnabled", true));
    }
}
