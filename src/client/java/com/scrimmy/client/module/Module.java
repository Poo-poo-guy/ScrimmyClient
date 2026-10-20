package com.scrimmy.client.module;

import com.scrimmy.client.setting.Setting;
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
    private int keybind = -1;
    private Runnable onChange = () -> {};

    protected Module(String name, String description, Category category) {
        this.name = name; this.description = description; this.category = category;
    }
    protected <T extends Setting<?>> T add(T setting) { settings.add(setting); return setting; }
    public String name() { return name; }
    public String description() { return description; }
    public Category category() { return category; }
    public List<Setting<?>> settings() { return settingsView; }
    public boolean enabled() { return enabled; }
    public int keybind() { return keybind; }
    public void keybind(int key) { if (keybind != key) { keybind = key; onChange.run(); } }
    public void onChange(Runnable callback) {
        onChange = callback;
        settings.forEach(s -> s.onChange(callback));
    }
    public void enabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) onEnable(); else onDisable();
        onChange.run();
    }
    public void toggle() { enabled(!enabled); }
    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick(Minecraft client) {}
}
