package com.scrimmy.client.config;

import com.google.gson.*;
import com.scrimmy.client.setting.*;
import java.util.ArrayList;
import java.util.List;

/** The same validated codec is used for module settings and user presets. */
public final class SettingCodec {
    private SettingCodec() {}
    public static void write(JsonObject object, Setting<?> setting) {
        if (setting instanceof ActionSetting) return;
        Object value = setting.get();
        if (setting instanceof BlockListSetting blocks) {
            JsonObject entries = new JsonObject();
            blocks.get().forEach(entries::addProperty); object.add(setting.key(), entries);
        } else if (setting instanceof GradientSetting gradient) {
            JsonArray array = new JsonArray();
            for (GradientSetting.Stop stop : gradient.get()) {
                JsonObject entry = new JsonObject(); entry.addProperty("position", stop.position()); entry.addProperty("color", stop.color());
                array.add(entry);
            }
            object.add(setting.key(), array);
        } else if (setting instanceof MultiSelectSetting multi) {
            JsonArray array = new JsonArray(); multi.get().forEach(array::add); object.add(setting.key(), array);
        } else if (value instanceof Number n) object.addProperty(setting.key(), n);
        else if (value instanceof Boolean b) object.addProperty(setting.key(), b);
        else object.addProperty(setting.key(), String.valueOf(value));
    }
    public static void read(JsonObject object, Setting<?> setting) {
        if (!object.has(setting.key())) return;
        try {
            JsonElement value = object.get(setting.key());
            if (setting instanceof BlockListSetting s) {
                java.util.Map<String, Integer> entries = new java.util.LinkedHashMap<>();
                for (var entry : value.getAsJsonObject().entrySet()) {
                    try { entries.put(entry.getKey(), entry.getValue().getAsInt()); }
                    catch (RuntimeException ignored) { }
                }
                s.set(entries);
            } else if (setting instanceof BooleanSetting s) s.set(value.getAsBoolean());
            else if (setting instanceof GroupSetting s) s.set(value.getAsBoolean());
            else if (setting instanceof NumberSetting s) s.set(value.getAsDouble());
            else if (setting instanceof ModeSetting s) s.set(value.getAsString());
            else if (setting instanceof ColorSetting s) s.set(value.getAsInt());
            else if (setting instanceof MultiSelectSetting s) {
                List<String> entries = new ArrayList<>(); value.getAsJsonArray().forEach(e -> entries.add(e.getAsString())); s.set(entries);
            } else if (setting instanceof GradientSetting s) {
                List<GradientSetting.Stop> entries = new ArrayList<>();
                for (JsonElement e : value.getAsJsonArray()) {
                    JsonObject stop = e.getAsJsonObject();
                    entries.add(new GradientSetting.Stop(stop.get("position").getAsDouble(), stop.get("color").getAsInt()));
                }
                s.set(entries);
            }
        } catch (RuntimeException ignored) { /* Keep all other valid fields when one entry is corrupt. */ }
    }
}
