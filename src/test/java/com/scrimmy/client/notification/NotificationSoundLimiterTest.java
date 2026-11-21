package com.scrimmy.client.notification;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationSoundLimiterTest {
    @Test void onlyOneSoundCanStartPerClientTickEvenAcrossBothFlushPoints() {
        var limiter = new NotificationSoundLimiter();
        limiter.beginTick();
        assertTrue(limiter.claim(true));
        for (int i = 0; i < 20; i++) assertFalse(limiter.claim(true));
        limiter.beginTick(); assertTrue(limiter.claim(true)); assertFalse(limiter.claim(true));
    }
    @Test void mutedOrUnavailableRequestsDoNotConsumeTheAudibleSlot() {
        var limiter = new NotificationSoundLimiter();
        assertFalse(limiter.claim(false)); assertFalse(limiter.claim(false));
        assertTrue(limiter.claim(true)); assertFalse(limiter.claim(true));
        limiter.beginTick(); assertFalse(limiter.claim(false)); assertTrue(limiter.claim(true));
    }
    @Test void offAlwaysHasLowerPitchIncludingBothVariationExtremes() {
        assertEquals(1, NotificationSoundLimiter.pitch(true, false, 0));
        assertEquals(.82f, NotificationSoundLimiter.pitch(false, false, 1));
        float highestOff = NotificationSoundLimiter.pitch(false, true, 1);
        float lowestOn = NotificationSoundLimiter.pitch(true, true, 0);
        assertTrue(highestOff < lowestOn);
        assertEquals(.965f, lowestOn, .00001);
        assertEquals(.855f, highestOff, .00001);
        assertNotEquals(NotificationSoundLimiter.pitch(true, true, 0), NotificationSoundLimiter.pitch(true, true, 1));
    }
    @Test void audioControlsAreGroupedPersistedAndVolumeSupportsMute() {
        var settings = new NotificationSettings();
        assertTrue(settings.sound.get()); assertTrue(settings.pitchVariation.get()); assertEquals(.25, settings.volume.get());
        assertSame(settings.section, settings.sound.group()); assertSame(settings.section, settings.volume.group());
        assertSame(settings.section, settings.pitchVariation.group());
        settings.sound.set(false); settings.volume.set(0.0); settings.pitchVariation.set(false);
        JsonObject saved = new JsonObject(); settings.settings().forEach(s -> SettingCodec.write(saved, s));
        var restored = new NotificationSettings(); restored.settings().forEach(s -> SettingCodec.read(saved, s));
        assertFalse(restored.sound.get()); assertEquals(0, restored.volume.get()); assertFalse(restored.pitchVariation.get());
        restored.volume.set(10.0); assertEquals(1, restored.volume.get());
    }
}
