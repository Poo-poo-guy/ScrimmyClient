package com.scrimmy.client.config;

import com.google.gson.*;

/** One-way aliases for retained controls. Removed tuning is deliberately never read. */
public final class ModuleSettings {
    private static final String[] BLOCK_GROUPS = {"Storage", "Ores", "Ancient Debris", "Spawners", "Portals", "Beds", "Respawn Anchors"};
    private ModuleSettings() {}

    public static JsonObject migrate(String module, JsonObject source) {
        JsonObject values = source.deepCopy();
        if (module.equals("Basefinding")) {
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
