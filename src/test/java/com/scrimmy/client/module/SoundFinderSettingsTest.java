package com.scrimmy.client.module;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.sound.SoundTracker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoundFinderSettingsTest {
    @Test void safeDefaultsSettingsAndEditablePrefixesSurviveRestore() {
        SoundFinder module = new SoundFinder(), restored = new SoundFinder();
        assertFalse(module.enabled()); assertEquals(Category.BASEFINDING,module.category());
        assertTrue(module.throughWalls.get()); assertFalse(module.chunkHighlight.get()); assertFalse(module.entitySounds.get());
        assertFalse(module.logDetections.get()); assertEquals(30,module.duration.get()); assertEquals(256,module.maxSources.get());
        assertEquals(4,module.angle.get()); assertEquals("Look-at only",module.nametagMode.get());
        assertNotNull(new ModuleManager().find(SoundFinder.class));
        module.ignoreList("weather., modid:block.custom.; entity.player.swim\nambient.");
        module.chunkHighlight.set(true); module.maxSources.set(512.0); module.redColor.set(0xffcc5566);
        JsonObject settings = new JsonObject(); module.settings().forEach(s -> SettingCodec.write(settings,s));
        restored.settings().forEach(s -> SettingCodec.read(settings,s)); restored.extraConfig(module.extraConfig());
        assertEquals(module.ignoreList(),restored.ignoreList()); assertTrue(restored.chunkHighlight.get());
        assertEquals(512,restored.maxSources.get()); assertEquals(0xffcc5566,restored.redColor.get());
        String[] prefixes=SoundFinder.parsePrefixes(restored.ignoreList());
        assertTrue(SoundTracker.ignored("minecraft:weather.rain",prefixes));
        assertTrue(SoundTracker.ignored("modid:block.custom.click",prefixes));
        assertFalse(SoundTracker.ignored("minecraft:block.chest.open",prefixes));
        restored.ignoreList(""); assertEquals(0,SoundFinder.parsePrefixes(restored.ignoreList()).length);
        restored.duration.set(Double.NaN); assertEquals(30,restored.duration.get());
        restored.mediumThreshold.set(20.0);restored.redThreshold.set(2.0);assertEquals(0,restored.severity(10));
        assertEquals(2,restored.severity(20));
    }
}
