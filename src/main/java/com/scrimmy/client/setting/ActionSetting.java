package com.scrimmy.client.setting;

/** A command styled like a setting; it has no persisted value. */
public final class ActionSetting extends Setting<Boolean> {
    private final Runnable action;
    public ActionSetting(String key, String label, Runnable action) {
        super(key, label, false);
        this.action = action;
    }
    public void run() { action.run(); }
}
