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
    @Test void gradientStopsStaySortedAndRetainAlphaWhileEditing() {
        GradientSetting gradient = new GradientSetting("gradient", "Gradient", 0x00ff0000, 0xff0000ff);
        assertEquals(0x00ff0000, gradient.sample(-1));
        assertEquals(0xff0000ff, gradient.sample(2));
        assertEquals(127, gradient.sample(.5) >>> 24);
        gradient.insert();
        assertEquals(3, gradient.get().size());
        int selected = gradient.position(1, .9);
        gradient.color(selected, 0x40776655);
        assertEquals(0x40776655, gradient.get().get(selected).color());
        gradient.remove(selected); gradient.remove(0);
        assertEquals(2, gradient.get().size());
        ColorSetting color = new ColorSetting("color", "Color", 0xff112233);
        color.set(0x00123456); assertEquals(0x00123456, color.get());
    }
}
