package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.basefinding.ActivityLog;
import com.scrimmy.client.setting.GroupSetting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityLogSettingsTest {
    @Test void captureIsOptInAndSettingsRoundTripWithinTheDetectionCategory(){
        ActivityLog module=new ActivityLog(),restored=new ActivityLog();
        assertFalse(module.enabled());assertEquals(Category.BASEFINDING,module.category());
        assertEquals(5000,module.maxEntries.get());assertEquals(0,module.expireDays.get());
        assertTrue(module.sounds.get());assertTrue(module.players.get());assertFalse(module.items.get());assertFalse(module.mobs.get());
        assertFalse(module.undergroundOnly.get());assertEquals("Recent only",module.markerMode.get());
        assertEquals(4,module.settings().stream().filter(s->s instanceof GroupSetting).count());
        assertNotNull(new ModuleManager().find(ActivityLog.class));
        module.items.set(true);module.undergroundOnly.set(true);module.mergeRadius.set(16.0);module.mergeWindow.set(90.0);
        module.markerMode.set("All within a distance");module.panelKey.set(75.0);module.maxEntries.set(1000.0);
        JsonObject values=new JsonObject();module.settings().forEach(s->SettingCodec.write(values,s));restored.settings().forEach(s->SettingCodec.read(values,s));restored.extraConfig(module.extraConfig());
        for(int i=0;i<module.settings().size();i++)assertEquals(module.settings().get(i).get(),restored.settings().get(i).get());
        assertEquals(16,restored.mergeRadius.get());assertTrue(restored.items.get());assertEquals(75,restored.panelKey.get());
    }
}
