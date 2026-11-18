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
        assertEquals(List.of("mode","range","minimumLength","dimVisited","showDirection","nametags"),source.settings().stream().map(s->s.key()).toList());
        assertEquals("Smart",source.mode.get());assertTrue(source.nametags.get());
        assertTrue(source.settings().stream().allMatch(s->s.tooltip()!=null&&!s.tooltip().isBlank()));
        source.mode.set("Normal");source.nametags.set(false);
        source.range.set(160.0); source.dimVisited.set(false); source.minimumLength.set(24.0);
        JsonObject values=new JsonObject();source.settings().forEach(s->SettingCodec.write(values,s));values.addProperty("oldRemovedField","bad");
        restored.settings().forEach(s->SettingCodec.read(values,s));
        assertEquals("Normal",restored.mode.get());assertFalse(restored.nametags.get());
        assertEquals(160,restored.range.get());assertFalse(restored.dimVisited.get());assertEquals(24,restored.minimumLength.get());
        restored.settings().forEach(s->SettingCodec.read(new JsonObject(),s));assertEquals(160,restored.range.get());
        JsonObject old=new JsonObject();old.addProperty("markLeads",false);old.addProperty("hintHud",true);old.addProperty("mode","broken");
        restored.settings().forEach(s->SettingCodec.read(old,s));assertEquals("Smart",restored.mode.get());
        assertEquals(0,new Basefinding().scoreAtChunk(0,0));
        assertNotNull(new ModuleManager().find(DrillTrail.class));
    }
}
