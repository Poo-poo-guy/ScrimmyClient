package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.animation.Animation;
import com.scrimmy.client.animation.AnimationClock;
import com.scrimmy.client.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared SDF TrueType renderer with cached fractional layouts, font fades, and Inter fallback. */
public final class ClientFonts {
    private static final List<Family> FAMILIES = new ArrayList<>();
    private static int revision;
    private static net.minecraft.client.gui.Font widgetFont;
    static {
        for (var entry : com.scrimmy.client.theme.FontCatalog.entries()) register(entry.name(), entry.resource());
    }
    private ClientFonts() {}
    public static void register(String name, String resource) { FAMILIES.add(new Family(name, resource, FAMILIES.size(), name.equals("Inter"))); }
    public static String[] names() { return FAMILIES.stream().map(f -> f.name).toArray(String[]::new); }
    public static Style selectedStyle() {
        for (Family family : FAMILIES) if (family.name.equals(ScrimmyClient.THEME.font.get())) return family.styles[0];
        return FAMILIES.getFirst().styles[0];
    }
    public static Component component(String text) { return Component.literal(text).setStyle(selectedStyle()); }
    /** Text entry uses the same selected TrueType provider; the widget owns caret/selection layout. */
    public static net.minecraft.client.gui.Font widgetFont() {
        if (widgetFont == null) {
            var provider = ((com.scrimmy.client.mixin.FontAccessor)Minecraft.getInstance().font).scrimmy$provider();
            widgetFont = new net.minecraft.client.gui.Font(new net.minecraft.client.gui.Font.Provider() {
                @Override public net.minecraft.client.gui.GlyphSource glyphs(FontDescription description) {
                    FontDescription selected = description.equals(FontDescription.DEFAULT) ? selectedStyle().getFont() : description;
                    var source = SdfFonts.source(selected);
                    return source != null ? source : provider.glyphs(selected);
                }
                @Override public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() { return provider.effect(); }
            });
        }
        return widgetFont;
    }
    public static int revision() { return revision; }
    public static void frame() {
        SdfFonts.frame();
        var theme = ScrimmyClient.THEME;
        double dt = AnimationClock.UI.delta() * theme.animationSpeed.get();
        for (int i = 0; i < FAMILIES.size(); i++) {
            Family family = FAMILIES.get(i);
            family.weight.spring(family.name.equals(theme.font.get()) ? 1 : 0, dt, UiTokens.STATE_FREQUENCY, 1);
        }
    }
    /** Glyph generations own native textures; reload and shutdown release them on the render thread. */
    public static void reload() { SdfFonts.reload(); atlasReady(); }
    public static void atlasReady() {
        for (Family family : FAMILIES) { family.regular.clear(); family.bold.clear(); }
        revision++;
    }
    public static void close() { SdfFonts.close(); }
    public static int width(String text) { return (int)Math.ceil(width(text, false)); }
    public static float widthExact(String text) { return width(text, false); }
    public static int widthBold(String text) { return (int)Math.ceil(width(text, true)); }
    private static float width(String text, boolean bold) {
        float width = 0;
        for (int i = 0; i < FAMILIES.size(); i++) {
            Family f = FAMILIES.get(i);
            if (f.weight.value() > UiTokens.MIN_ALPHA) width += f.layout(text, bold).width * f.weight.value();
        }
        return width;
    }
    public static void draw(GuiGraphics g, String text, float x, float y, int color) { draw(g, text, x, y, color, false); }
    public static void drawBold(GuiGraphics g, String text, float x, float y, int color) { draw(g, text, x, y, color, true); }
    private static void draw(GuiGraphics g, String text, float x, float y, int color, boolean bold) {
        for (int i = 0; i < FAMILIES.size(); i++) {
            Family f = FAMILIES.get(i);
            int alpha = Math.round((color >>> 24) * f.weight.value());
            if (alpha < 1) continue; // Vanilla normalizes zero alpha; never submit invisible text.
            Layout layout = f.layout(text, bold);
            int tint = (alpha << 24) | (color & 0xffffff);
            for (int run = 0; run < layout.runs.length; run++) {
                g.pose().pushMatrix();
                g.pose().translate(x + layout.offsets[run], y);
                g.drawString(Minecraft.getInstance().font, layout.runs[run], 0, 0, tint, false);
                g.pose().popMatrix();
            }
        }
    }
    private static int layoutWidth(String text) {
        float width = 0;
        for (Family family : FAMILIES) width = Math.max(width, family.layout(text, false).width);
        return (int)Math.ceil(width);
    }
    public static String[] wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && layoutWidth(line + " " + word) > width) { lines.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.toArray(String[]::new);
    }
    private record Layout(Component[] runs, float[] offsets, float width) {}
    private static final class Family {
        final String name;
        final int index;
        final Animation weight;
        final Style[] styles = new Style[2];
        final Map<String, Layout> regular = new LinkedHashMap<>(), bold = new LinkedHashMap<>();
        Family(String name, String resource, int index, boolean selected) {
            this.name = name; this.index = index; weight = new Animation(selected ? 1 : 0);
            for (int i = 0; i < 2; i++) {
                String variant = i == 0 ? "regular" : "semibold";
                styles[i] = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("scrimmyclient", resource + "_" + variant)));
            }
        }
        Layout layout(String text, boolean semibold) {
            Map<String, Layout> cache = semibold ? bold : regular;
            Layout result = cache.get(text);
            if (result != null) return result;
            if (cache.size() >= 2048) cache.remove(cache.keySet().iterator().next());
            int weight = semibold ? 1 : 0, start = 0;
            float pen = 0;
            List<Component> runs = new ArrayList<>();
            List<Float> offsets = new ArrayList<>();
            for (int i = text.isEmpty() ? 0 : Character.charCount(text.codePointAt(0)); i < text.length(); i += Character.charCount(text.codePointAt(i))) {
                int a = text.codePointBefore(i), b = text.codePointAt(i);
                float kern = SdfFonts.kerning(index, semibold, a, b);
                if (kern == 0) continue;
                Component run = Component.literal(text.substring(start, i)).setStyle(styles[weight]);
                runs.add(run); offsets.add(pen);
                pen += Minecraft.getInstance().font.getSplitter().stringWidth(run) + kern;
                start = i;
            }
            Component run = Component.literal(text.substring(start)).setStyle(styles[weight]);
            runs.add(run); offsets.add(pen);
            pen += Minecraft.getInstance().font.getSplitter().stringWidth(run);
            float[] positions = new float[offsets.size()];
            for (int i = 0; i < positions.length; i++) positions[i] = offsets.get(i);
            result = new Layout(runs.toArray(Component[]::new), positions, pen);
            cache.put(text, result);
            return result;
        }
    }
}
