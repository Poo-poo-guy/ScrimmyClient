package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.basefinding.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DrillTrailSettingsTest {
    @Test void exactlyTheRequestedControlsRestoreFromFreshAndOldProfiles() {
        DrillTrail source=new DrillTrail(), restored=new DrillTrail();
        assertEquals(Category.BASEFINDING,source.category()); assertFalse(source.enabled());
        assertEquals(List.of("range","dimVisited","markLeads","showDirection","hintHud","minimumLength"),source.settings().stream().map(s->s.key()).toList());
        source.range.set(160.0); source.dimVisited.set(false); source.minimumLength.set(24.0);
        JsonObject values=new JsonObject();source.settings().forEach(s->SettingCodec.write(values,s));values.addProperty("oldRemovedField","bad");
        restored.settings().forEach(s->SettingCodec.read(values,s));
        assertEquals(160,restored.range.get());assertFalse(restored.dimVisited.get());assertEquals(24,restored.minimumLength.get());
        restored.settings().forEach(s->SettingCodec.read(new JsonObject(),s));assertEquals(160,restored.range.get());
        assertEquals(0,new Basefinding().scoreAtChunk(0,0));
        assertNotNull(new ModuleManager().find(DrillTrail.class));
    }
}
