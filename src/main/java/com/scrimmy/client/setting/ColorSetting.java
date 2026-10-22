package com.scrimmy.client.setting;

public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String key, String label, int initial) { super(key, label, initial); }
    @Override protected Integer sanitize(Integer value) { return value == null ? defaultValue() : value; }
}
