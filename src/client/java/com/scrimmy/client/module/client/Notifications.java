package com.scrimmy.client.module.client;

import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.setting.ModeSetting;
import com.scrimmy.client.setting.NumberSetting;

public final class Notifications extends Module {
    public final ModeSetting position = add(new ModeSetting("position", "Position", "Top Right",
            "Top Right", "Bottom Right", "Top Left", "Bottom Left"));
    public final NumberSetting duration = add(new NumberSetting("duration", "Duration (s)", 2.5, 1, 6, .25));
    public final ModeSetting source = add(new ModeSetting("source", "Source", "Keybind only", "Keybind only", "All toggles"));
    public Notifications() {
        super("Notifications", "Glass notifications when modules change state", Category.CLIENT, true);
        position.tooltip("Choose the screen corner where notifications stack.");
        duration.tooltip("How long each notification holds before fading away.");
        source.tooltip("Show keybind changes only, or include ClickGUI and other module changes.");
    }
}
