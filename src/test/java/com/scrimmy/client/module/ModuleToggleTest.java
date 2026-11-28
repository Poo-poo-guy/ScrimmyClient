package com.scrimmy.client.module;

import com.scrimmy.client.notification.NotificationSettings;
import com.scrimmy.client.theme.Theme;
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
            // Redundant setters remain silent for every registered module.
            module.enabled(module.enabled(), Module.ToggleSource.KEYBIND);
        }
        assertTrue(sources.isEmpty());
        Module target = manager.find(com.scrimmy.client.module.visual.TypingAnimation.class);
        target.toggle(Module.ToggleSource.KEYBIND);
        target.toggle(Module.ToggleSource.CLICK_GUI);
        target.enabled(true);
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
    @Test void notificationsLiveInScrimmyClientPlusAndNoSeparateModuleIsRegistered() {
        Theme theme = new Theme(); NotificationSettings settings = theme.notifications;
        assertTrue(settings.enabled.get());
        assertEquals("Keybind only", settings.source.get()); assertEquals("Top Right", settings.position.get());
        assertEquals(2.5, settings.duration.get());
        assertTrue(theme.settings().containsAll(settings.settings()));
        for (Module module : new ModuleManager().all()) assertNotEquals("Notifications", module.name());
        for (var setting : settings.settings()) if (setting != settings.section) assertSame(settings.section, setting.group());
    }
}
