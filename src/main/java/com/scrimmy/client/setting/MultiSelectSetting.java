package com.scrimmy.client.setting;

import java.util.ArrayList;
import java.util.List;

public final class MultiSelectSetting extends Setting<List<String>> {
    private final List<String> options;
    private final boolean allowEmpty;
    public MultiSelectSetting(String key, String label, List<String> initial, String... options) {
        this(key, label, initial, false, options);
    }
    public MultiSelectSetting(String key, String label, List<String> initial, boolean allowEmpty, String... options) {
        super(key, label, List.copyOf(initial));
        this.allowEmpty = allowEmpty;
        this.options = List.of(options);
    }
    public List<String> options() { return options; }
    public void toggle(String option) {
        List<String> next = new ArrayList<>(get());
        if (!next.remove(option)) next.add(option);
        set(next);
    }
    @Override protected List<String> sanitize(List<String> value) {
        if (value == null) return defaultValue();
        List<String> safe = value.stream().filter(options::contains).distinct().toList();
        return safe.isEmpty() && !allowEmpty ? get() : safe;
    }
}
