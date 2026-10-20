package com.scrimmy.client.setting;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> options;
    public ModeSetting(String key, String label, String initial, String... options) {
        super(key, label, initial);
        this.options = List.of(options);
    }
    public List<String> options() { return options; }
    public void next() { set(options.get((options.indexOf(get()) + 1) % options.size())); }
    @Override protected String sanitize(String value) { return options.contains(value) ? value : defaultValue(); }
}
