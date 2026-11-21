package com.scrimmy.client.config;

import com.google.gson.*;

/** One-way aliases for retained controls. Removed tuning is deliberately never read. */
public final class ModuleSettings {
    private static final String[] BLOCK_GROUPS = {"Storage", "Ores", "Ancient Debris", "Spawners", "Portals", "Beds", "Respawn Anchors"};
    private ModuleSettings() {}

    /** The canonical key wins if a config contains both names. */
    public static JsonObject entry(JsonObject modules, String name) {
        JsonElement value = modules.get(name);
        if (value == null && name.equals("ChunkFinder")) value = modules.get("Basefinding");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    public static JsonObject appearance(JsonObject source) {
        JsonObject values = source.deepCopy();
        if (!values.has("animation") && values.has("easing"))
            values.addProperty("animation", "Bouncy".equals(string(values, "easing", "Smooth")) ? "Spring" : "Smooth");
        return values;
    }

    /** Lift the retired module's controls into ScrimmyClient+; each new canonical key wins. */
    public static JsonObject appearance(JsonObject source, JsonObject modules) {
        JsonObject values = appearance(source);
        JsonObject legacy = entry(modules, "Notifications");
        if (!values.has("notificationsEnabled") && legacy.has("enabled"))
            values.add("notificationsEnabled", legacy.get("enabled").deepCopy());
        JsonElement oldSettings = legacy.get("settings");
        if (oldSettings != null && oldSettings.isJsonObject()) {
            JsonObject old = oldSettings.getAsJsonObject();
            for (String key : new String[]{"position", "duration", "source"}) {
                String target = "notifications" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                if (!values.has(target) && old.has(key)) values.add(target, old.get(key).deepCopy());
            }
        }
        if (!values.has("notificationsOpacity") && source.has("opacity"))
            values.add("notificationsOpacity", source.get("opacity").deepCopy());
        return values;
    }

    public static JsonObject migrate(String module, JsonObject source) {
        JsonObject values = source.deepCopy();
        if ((module.equals("ChunkFinder") || module.equals("Basefinding"))) {
            if (!values.has("cfRange") && values.has("cfDistance")) {
                try { values.addProperty("cfRange", values.get("cfDistance").getAsDouble() / 16); }
                catch (RuntimeException ignored) { }
            }
            if (!values.has("cfHighlightChunks") && (values.has("cfFill") || values.has("cfOutline")))
                values.addProperty("cfHighlightChunks", bool(values, "cfFill", true) || bool(values, "cfOutline", true));
        }
        if (module.equals("BlockESP") && !values.has("blockGroups")) {
            JsonArray groups = new JsonArray();
            for (int i = 0; i < BLOCK_GROUPS.length; i++)
                if (bool(values, "group" + i, i == 0 || i == 2 || i == 3)) groups.add(BLOCK_GROUPS[i]);
            values.add("blockGroups", groups);
        }
        if (module.equals("HitParticles") && "Custom".equals(string(values, "preset", "Starburst"))) {
            String style = switch (string(values, "shape", "Star")) {
                case "Sparkle" -> "Sparkle";
                case "Heart" -> "Hearts";
                case "Circle" -> "Minimal";
                case "Random mix" -> "Spiral".equals(string(values, "motion", "")) ? "Galaxy" : "Confetti";
                default -> "Starburst";
            };
            values.addProperty("preset", style);
        }
        return values;
    }
    public static boolean bool(JsonObject values, String key, boolean fallback) {
        try { return values.has(key) ? values.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static String string(JsonObject values, String key, String fallback) {
        try { return values.has(key) ? values.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
}
