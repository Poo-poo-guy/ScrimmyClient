package com.scrimmy.client.setting;

public final class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String key, String label, boolean initial) { super(key, label, initial); }
    public void toggle() { set(!get()); }
}
