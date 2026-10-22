package com.scrimmy.client.setting;

/** A persisted, collapsible subsection, shared by rendering and input layout. */
public final class GroupSetting extends Setting<Boolean> {
    public GroupSetting(String key, String label, boolean open) { super(key, label, open); }
    public void toggle() { set(!get()); }
}
