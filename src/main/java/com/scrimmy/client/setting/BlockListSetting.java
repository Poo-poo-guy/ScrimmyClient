package com.scrimmy.client.setting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** A bounded registry-ID list with an independent color for each entry. */
public final class BlockListSetting extends Setting<Map<String, Integer>> {
    public static final int LIMIT = 256;
    private final Predicate<String> valid;

    public BlockListSetting(String key, String label, Predicate<String> valid) {
        super(key, label, Map.of());
        this.valid = valid;
    }
    public boolean put(String id, int color) {
        if (id == null) return false;
        id = id.trim();
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!validId(id) || (!get().containsKey(id) && get().size() >= LIMIT)) return false;
        Map<String, Integer> next = new LinkedHashMap<>(get());
        next.put(id, color); set(next); return true;
    }
    public void remove(String id) {
        Map<String, Integer> next = new LinkedHashMap<>(get());
        next.remove(id); set(next);
    }
    private boolean validId(String id) {
        return id.length() <= 160 && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && valid.test(id);
    }
    @Override protected Map<String, Integer> sanitize(Map<String, Integer> value) {
        Map<String, Integer> safe = new LinkedHashMap<>();
        if (value != null) for (var entry : value.entrySet()) {
            if (safe.size() == LIMIT) break;
            if (entry.getKey() != null && entry.getValue() != null && validId(entry.getKey())) safe.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(safe);
    }
}
