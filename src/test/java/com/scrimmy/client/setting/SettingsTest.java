package com.scrimmy.client.setting;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void numberSettingRejectsInvalidAndClampsToStep() {
        NumberSetting delay = new NumberSetting("delay", "Delay", 4, 0, 20, 1);
        delay.set(999.0);
        assertEquals(20.0, delay.get());
        delay.set(-7.0);
        assertEquals(0.0, delay.get());
        delay.set(3.7);
        assertEquals(4.0, delay.get());
        delay.set(Double.NaN);
        assertEquals(4.0, delay.get());
    }

    @Test void invalidModeCannotEnterConfigAndChangesNotifyOnce() {
        ModeSetting mode = new ModeSetting("mode", "Mode", "Always", "Always", "Health threshold");
        AtomicInteger changes = new AtomicInteger();
        mode.onChange(changes::incrementAndGet);
        mode.set("unknown");
        assertEquals("Always", mode.get());
        assertEquals(0, changes.get());
        mode.next();
        assertEquals("Health threshold", mode.get());
        assertEquals(1, changes.get());
    }
}
