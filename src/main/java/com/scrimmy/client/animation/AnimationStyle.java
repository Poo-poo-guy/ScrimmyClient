package com.scrimmy.client.animation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Add a style with one registration; settings derive their options from this registry. */
public record AnimationStyle(String name, Easing easing, double duration, double frequency,
                             double damping, float stagger, float slide, float zoom) {
    private static final Map<String, AnimationStyle> STYLES = new LinkedHashMap<>();
    static {
        register(new AnimationStyle("Smooth", Easing.OUT_QUINT, .32, 19, 1, 0, 12, 0));
        register(new AnimationStyle("Spring", Easing.OUT_BACK, .4, 18, .88, 0, 9, 0));
        register(new AnimationStyle("Cascade", Easing.OUT_CUBIC, .30, 19, 1, .025f, 16, 0));
        register(new AnimationStyle("Zoom", Easing.IN_OUT, .28, 21, 1, 0, 0, .08f));
    }
    public static void register(AnimationStyle style) { STYLES.put(style.name(), style); }
    public static String[] names() { return STYLES.keySet().toArray(String[]::new); }
    public static AnimationStyle get(String name) { return STYLES.getOrDefault(name, STYLES.get("Smooth")); }
}
