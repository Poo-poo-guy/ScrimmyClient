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
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared FreeType/TrueType renderer. Minecraft owns lazy GPU glyph atlases and reload disposal.
 * Text runs/metrics are cached; GPOS pair adjustments are applied before atlas rendering. */
public final class ClientFonts {
    private static final List<Family> FAMILIES = new ArrayList<>();
    private static int revision;
    private static net.minecraft.client.gui.Font widgetFont;
    static {
        for (var entry : com.scrimmy.client.theme.FontCatalog.entries()) register(entry.name(), entry.resource());
    }
    private ClientFonts() {}
    public static void register(String name, String resource) { FAMILIES.add(new Family(name, resource, FAMILIES.isEmpty())); }
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
                    return provider.glyphs(description.equals(FontDescription.DEFAULT) ? selectedStyle().getFont() : description);
                }
                @Override public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() { return provider.effect(); }
            });
        }
        return widgetFont;
    }
    public static int revision() { return revision; }
    public static void frame() {
        var theme = ScrimmyClient.THEME;
        double dt = AnimationClock.UI.delta() * theme.animationSpeed.get();
        for (int i = 0; i < FAMILIES.size(); i++) {
            Family family = FAMILIES.get(i);
            family.weight.spring(family.name.equals(theme.font.get()) ? 1 : 0, dt, UiTokens.STATE_FREQUENCY, 1);
        }
    }
    /** Called after a resource reload on the render thread. No stale baked glyph references survive. */
    public static void reload() {
        for (Family family : FAMILIES) {
            family.regular.clear(); family.bold.clear();
            for (int weight = 0; weight < 2; weight++) {
                Style style = family.styles[weight];
                Component warmup = Component.literal(" !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~").setStyle(style);
                Minecraft.getInstance().font.prepareText(warmup.getVisualOrderText(), 0, 0, 0xffffffff, false, false, 0);
            }
        }
        revision++;
    }
    public static int width(String text) { return width(text, false); }
    public static int widthBold(String text) { return width(text, true); }
    private static int width(String text, boolean bold) {
        float width = 0;
        for (int i = 0; i < FAMILIES.size(); i++) {
            Family f = FAMILIES.get(i);
            if (f.weight.value() > UiTokens.MIN_ALPHA) width += f.layout(text, bold).width * f.weight.value();
        }
        return (int)Math.ceil(width);
    }
    public static void draw(GuiGraphics g, String text, int x, int y, int color) { draw(g, text, x, y, color, false); }
    public static void drawBold(GuiGraphics g, String text, int x, int y, int color) { draw(g, text, x, y, color, true); }
    private static void draw(GuiGraphics g, String text, int x, int y, int color, boolean bold) {
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
        final Animation weight;
        final Style[] styles = new Style[2];
        final float[][] kerning = new float[2][224 * 224];
        final Map<String, Layout> regular = new LinkedHashMap<>(), bold = new LinkedHashMap<>();
        Family(String name, String resource, boolean first) {
            this.name = name; weight = new Animation(first ? 1 : 0);
            for (int i = 0; i < 2; i++) {
                String variant = i == 0 ? "regular" : "semibold";
                styles[i] = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("scrimmyclient", resource + "_" + variant)));
                String path = "/assets/scrimmyclient/font/" + resource.toLowerCase(Locale.ROOT) + "/" + variant + ".kern";
                try (var raw = ClientFonts.class.getResourceAsStream(path)) {
                    if (raw == null) throw new IOException("Missing kerning table " + path);
                    try (var input = new DataInputStream(raw)) {
                        while (input.available() > 0) {
                            int a = input.readUnsignedShort(), b = input.readUnsignedShort();
                            kerning[i][(a - 32) * 224 + b - 32] = input.readFloat();
                        }
                    }
                } catch (IOException e) { throw new IllegalStateException("Could not load bundled font metrics", e); }
            }
        }
        Layout layout(String text, boolean semibold) {
            Map<String, Layout> cache = semibold ? bold : regular;
            Layout result = cache.get(text);
            if (result != null) return result;
            if (cache.size() >= 2048) cache.remove(cache.keySet().iterator().next());
            int index = semibold ? 1 : 0, start = 0;
            float pen = 0;
            List<Component> runs = new ArrayList<>();
            List<Float> offsets = new ArrayList<>();
            for (int i = 1; i < text.length(); i++) {
                int a = text.charAt(i - 1), b = text.charAt(i);
                float kern = a >= 32 && a < 256 && b >= 32 && b < 256 ? kerning[index][(a - 32) * 224 + b - 32] : 0;
                if (kern == 0) continue;
                Component run = Component.literal(text.substring(start, i)).setStyle(styles[index]);
                runs.add(run); offsets.add(pen);
                pen += Minecraft.getInstance().font.getSplitter().stringWidth(run) + kern;
                start = i;
            }
            Component run = Component.literal(text.substring(start)).setStyle(styles[index]);
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
