package com.scrimmy.client.font;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.HashMap;
import java.util.Map;

/** Scale-independent fractional metrics and antialiased, padded glyph rasterization. */
public final class FontFace {
    public static final float SIZE = 11, BASELINE = 8, SYNTHETIC_STROKE = .24f;
    public static final FontRenderContext CONTEXT = new FontRenderContext(new AffineTransform(), true, true);
    private final Font font, fallback;
    private final boolean syntheticBold;
    private final Map<Long, Float> pairs = new HashMap<>();
    public FontFace(Font font, Font fallback, boolean syntheticBold) {
        this.font = font.deriveFont(Map.of(TextAttribute.SIZE, SIZE, TextAttribute.KERNING, TextAttribute.KERNING_ON));
        this.fallback = fallback.deriveFont(Map.of(TextAttribute.SIZE, SIZE, TextAttribute.KERNING, TextAttribute.KERNING_ON));
        this.syntheticBold = syntheticBold;
    }
    private Font font(int codepoint) { return font.canDisplay(codepoint) ? font : fallback; }
    public boolean supports(int codepoint) { return font.canDisplay(codepoint) || fallback.canDisplay(codepoint); }
    public boolean usesInter(int codepoint) { return !font.canDisplay(codepoint) && fallback.canDisplay(codepoint); }
    public float advance(int codepoint) {
        return font(codepoint).createGlyphVector(CONTEXT, Character.toChars(codepoint)).getGlyphMetrics(0).getAdvanceX();
    }
    public float kerning(int a, int b) {
        if (font(a) != font(b) || !supports(a) || !supports(b)) return 0;
        long key = ((long)a << 32) | b;
        Float cached = pairs.get(key);
        if (cached != null) return cached;
        Font face = font(a);
        char[] text = (new String(Character.toChars(a)) + new String(Character.toChars(b))).toCharArray();
        GlyphVector pair = face.layoutGlyphVector(CONTEXT, text, 0, text.length, Font.LAYOUT_LEFT_TO_RIGHT);
        // Ligatures/combining sequences retain Minecraft's codepoint flow; ordinary pairs get real GPOS kerning.
        float adjustment = pair.getNumGlyphs() == 2 ? (float)pair.getGlyphPosition(1).getX() - advance(a) : 0;
        pairs.put(key, adjustment);
        return adjustment;
    }
    public Raster rasterize(int codepoint, int rasterScale) {
        Font face = font(codepoint).deriveFont(SIZE * rasterScale);
        GlyphVector vector = face.createGlyphVector(CONTEXT, Character.toChars(codepoint));
        var bounds = vector.getPixelBounds(CONTEXT, 0, 0);
        float advance = advance(codepoint);
        if (bounds.isEmpty()) return new Raster(0, 0, 0, 0, advance, new byte[0]);
        boolean synthesize = syntheticBold && font.canDisplay(codepoint);
        int padding = DistanceField.PADDING + (synthesize ? (int)Math.ceil(SYNTHETIC_STROKE * rasterScale) : 0);
        int width = bounds.width + padding * 2, height = bounds.height + padding * 2;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(Color.WHITE);
            graphics.translate(padding - bounds.x, padding - bounds.y);
            var outline = vector.getOutline();
            graphics.fill(outline);
            if (synthesize) {
                graphics.setStroke(new BasicStroke(SYNTHETIC_STROKE * rasterScale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.draw(outline);
            }
        } finally { graphics.dispose(); }
        byte[] coverage = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
        return new Raster(width, height, (bounds.x - padding) / (float)rasterScale,
            BASELINE + (bounds.y - padding) / (float)rasterScale, advance, DistanceField.encode(coverage, width, height));
    }
    public record Raster(int width, int height, float left, float top, float advance, byte[] pixels) {}
}
