package com.scrimmy.client.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/** Appearance options and rendering targets come from the same registration. */
public record SurfaceStyle(String name, float glass, float tint, int background) {
    private static final Map<String, SurfaceStyle> STYLES = new LinkedHashMap<>();
    static {
        register(new SurfaceStyle("Glass", 1, 1, UiTokens.SOLID));
        register(new SurfaceStyle("Solid", 0, 0, UiTokens.SOLID));
    }
    public static void register(SurfaceStyle style) { STYLES.put(style.name(), style); }
    public static String[] names() { return STYLES.keySet().toArray(String[]::new); }
    public static SurfaceStyle get(String name) { return STYLES.getOrDefault(name, STYLES.get("Glass")); }
}
