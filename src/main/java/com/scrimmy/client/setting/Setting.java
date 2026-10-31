package com.scrimmy.client.setting;

import java.util.Objects;

public abstract class Setting<T> {
    private final String key;
    private final String label;
    private final T defaultValue;
    private T value;
    private Runnable onChange = () -> {};
    private GroupSetting group;
    private String tooltip;

    protected Setting(String key, String label, T defaultValue) {
        this.key = key;
        this.label = label;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String tooltip() { return tooltip == null ? label : tooltip; }
    public void tooltip(String text) { tooltip = text; }
    public T get() { return value; }
    public T defaultValue() { return defaultValue; }
    public void onChange(Runnable callback) { this.onChange = callback; }
    public GroupSetting group() { return group; }
    public void group(GroupSetting group) { this.group = group; }

    public void set(T next) {
        T safe = sanitize(next);
        if (!Objects.equals(safe, value)) {
            value = safe;
            onChange.run();
        }
    }

    public void reset() { set(defaultValue); }
    protected T sanitize(T value) { return value; }
}
