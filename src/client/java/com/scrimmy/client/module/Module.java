package com.scrimmy.client.module;

import com.scrimmy.client.setting.Setting;
import com.scrimmy.client.setting.GroupSetting;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
    private final String name, description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> settingsView = Collections.unmodifiableList(settings);
    private boolean enabled;
    private final boolean defaultEnabled;
    public enum ToggleSource { KEYBIND, CLICK_GUI, PROGRAMMATIC }
    @FunctionalInterface public interface ToggleListener { void changed(Module module, boolean enabled, ToggleSource source); }
    private ToggleListener toggleListener = (module, enabled, source) -> {};
    private int keybind = -1;
    private Runnable onChange = () -> {};
    private GroupSetting currentGroup;

    protected Module(String name, String description, Category category) {
        this(name, description, category, false);
    }
    protected Module(String name, String description, Category category, boolean defaultEnabled) {
        this.name = name; this.description = description; this.category = category;
        this.enabled = this.defaultEnabled = defaultEnabled;
    }
    protected <T extends Setting<?>> T add(T setting) {
        setting.group(currentGroup);
        settings.add(setting);
        setting.onChange(() -> { onSettingChanged(setting); onChange.run(); });
        return setting;
    }
    protected void group(String key, String label, boolean open) {
        currentGroup = null;
        currentGroup = add(new GroupSetting(key, label, open));
    }
    protected void onSettingChanged(Setting<?> setting) {}
    protected void changed() { onChange.run(); }
    public void beginRestore() {}
    public void endRestore() {}
    public JsonObject extraConfig() { return new JsonObject(); }
    public void extraConfig(JsonObject json) {}
    public void initialize() {}
    public String name() { return name; }
    public String description() { return description; }
    public Category category() { return category; }
    public List<Setting<?>> settings() { return settingsView; }
    public boolean enabled() { return enabled; }
    public boolean defaultEnabled() { return defaultEnabled; }
    public void onToggle(ToggleListener listener) { toggleListener = listener; }
    public int keybind() { return keybind; }
    public void keybind(int key) { if (keybind != key) { keybind = key; onChange.run(); } }
    public void onChange(Runnable callback) {
        onChange = callback;
    }
    public void enabled(boolean value) { enabled(value, ToggleSource.PROGRAMMATIC); }
    public void enabled(boolean value, ToggleSource source) {
        if (enabled == value) return;
        enabled = value;
        if (value) onEnable(); else onDisable();
        onChange.run();
        if (enabled == value) toggleListener.changed(this, value, source);
    }
    public void toggle() { toggle(ToggleSource.PROGRAMMATIC); }
    public void toggle(ToggleSource source) { enabled(!enabled, source); }
    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick(Minecraft client) {}
}
