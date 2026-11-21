package com.scrimmy.client.module;

import com.scrimmy.client.module.client.Notifications;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ModuleToggleTest {
    @Test void centralHookReportsTheActualStateAndSourceExactlyOnceForEveryRegisteredModule() {
        ModuleManager manager = new ModuleManager();
        var sources = new ArrayList<Module.ToggleSource>();
        manager.onToggle((module, enabled, source) -> {
            assertEquals(enabled, module.enabled()); sources.add(source);
        });
        for (Module module : manager.all()) {
            // Use the no-op lifecycle of a test module below for stateful world modules.
            module.enabled(module.enabled(), Module.ToggleSource.KEYBIND);
        }
        assertTrue(sources.isEmpty());
        Notifications notifications = manager.find(Notifications.class);
        notifications.toggle(Module.ToggleSource.KEYBIND);
        notifications.toggle(Module.ToggleSource.CLICK_GUI);
        notifications.enabled(false);
        assertEquals(java.util.List.of(Module.ToggleSource.KEYBIND, Module.ToggleSource.CLICK_GUI, Module.ToggleSource.PROGRAMMATIC), sources);
    }
    @Test void futureModulesUseTheSameHookAndLifecycleVetoDoesNotReportAFalseEnabledState() {
        Module module = new Module("Future module", "Test", Category.CLIENT) {};
        var states = new ArrayList<Boolean>();
        module.onToggle((changed, enabled, source) -> states.add(enabled));
        module.toggle(Module.ToggleSource.KEYBIND); module.toggle(Module.ToggleSource.CLICK_GUI);
        assertEquals(java.util.List.of(true, false), states);
        Module veto = new Module("Veto", "Test", Category.CLIENT) {
            @Override protected void onEnable() { enabled(false); }
        };
        veto.onToggle((changed, enabled, source) -> { assertFalse(enabled); assertFalse(changed.enabled()); });
        veto.enabled(true); assertFalse(veto.enabled());
    }
    @Test void notificationsHaveThreeControlsAndDefaultToEnabledWithKeybindOnly() {
        Notifications module = new Notifications();
        assertTrue(module.enabled()); assertTrue(module.defaultEnabled());
        assertEquals(Category.CLIENT, module.category()); assertEquals(3, module.settings().size());
        assertEquals("Keybind only", module.source.get()); assertEquals("Top Right", module.position.get());
        assertEquals(2.5, module.duration.get());
    }
}
