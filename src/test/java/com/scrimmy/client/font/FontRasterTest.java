package com.scrimmy.client.font;

import com.scrimmy.client.theme.FontCatalog;
import com.scrimmy.client.theme.Theme;
import org.junit.jupiter.api.Test;
import java.awt.Font;
import java.io.InputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FontRasterTest {
    private static Font load(String path) throws Exception {
        try (InputStream input = FontRasterTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return Font.createFont(Font.TRUETYPE_FONT, input);
        }
    }
    private static final String INTER = "/assets/scrimmyclient/font/inter/regular.ttf";
    private static final String XUONG = "/assets/scrimmyclient/fonts/Xuong.ttf";
    @Test void explicitCatalogContainsOnlyRequestedFontsAndTheirResources() throws Exception {
        assertEquals(List.of("Xuong", "Inter", "Poppins", "Montserrat"), new Theme().font.options());
        for (var entry : FontCatalog.entries()) {
            assertNotNull(load(entry.regular()));
            if (entry.semibold() != null) assertNotNull(load(entry.semibold()));
        }
        assertNull(FontCatalog.entries().getFirst().semibold());
        Theme theme = new Theme();
        theme.font.set("Roboto"); assertEquals("Inter", theme.font.get());
        theme.font.set("Xuong");
        var saved = new com.google.gson.JsonObject();
        com.scrimmy.client.config.SettingCodec.write(saved, theme.font);
        Theme restored = new Theme();
        com.scrimmy.client.config.SettingCodec.read(saved, restored.font);
        assertEquals("Xuong", restored.font.get());
    }
    @Test void sdfPreservesAntialiasedCoverageAndHasAnEmptyGutter() {
        byte[] coverage = new byte[25]; coverage[12] = (byte)255; coverage[13] = (byte)128;
        byte[] sdf = DistanceField.encode(coverage, 5, 5);
        assertTrue((sdf[12] & 255) > 127); assertEquals(128, sdf[13] & 255);
        assertTrue((sdf[0] & 255) < 127);
        byte[] empty = DistanceField.encode(new byte[25], 5, 5);
        for (byte pixel : empty) assertEquals(0, pixel & 255);
    }
    @Test void fractionalMetricsAndBaselineDoNotChangeWithAtlasResolution() throws Exception {
        Font inter = load(INTER); FontFace face = new FontFace(inter, inter, false);
        float advance = face.advance('W');
        assertNotEquals(Math.round(advance), advance);
        for (int scale : new int[]{3, 6, 9, 12}) {
            var glyph = face.rasterize('W', scale);
            assertEquals(advance, glyph.advance(), .00001);
            assertTrue(glyph.width() > DistanceField.PADDING * 2);
            for (int x = 0; x < glyph.width(); x++) {
                assertEquals(0, glyph.pixels()[x] & 255);
                assertEquals(0, glyph.pixels()[(glyph.height() - 1) * glyph.width() + x] & 255);
            }
        }
        assertTrue(face.kerning('A', 'V') < 0);
        assertEquals(face.kerning('A', 'V'), face.kerning('A', 'V'));
    }
    @Test void xuongFallsBackPerCodepointAndSyntheticTitlesKeepAdvances() throws Exception {
        Font xuong = load(XUONG), inter = load(INTER);
        FontFace regular = new FontFace(xuong, inter, false), bold = new FontFace(xuong, inter, true);
        int fallback = -1;
        for (int cp = 32; cp < 0x2000; cp++) if (!xuong.canDisplay(cp) && inter.canDisplay(cp)) { fallback = cp; break; }
        assertTrue(fallback >= 0); assertTrue(regular.usesInter(fallback));
        assertEquals(new FontFace(inter, inter, false).advance(fallback), regular.advance(fallback));
        assertEquals(regular.advance('A'), bold.advance('A'));
        var a = regular.rasterize('A', 6); var b = bold.rasterize('A', 6);
        assertTrue(a.pixels().length > 0); assertTrue(b.pixels().length > 0);
        long regularInk = 0, boldInk = 0;
        for (byte pixel : a.pixels()) if ((pixel & 255) >= 128) regularInk++;
        for (byte pixel : b.pixels()) if ((pixel & 255) >= 128) boldInk++;
        assertTrue(boldInk > regularInk, "Synthetic titles add coverage without changing advances");
        assertEquals(0, regular.rasterize(' ', 6).width());
    }
}
