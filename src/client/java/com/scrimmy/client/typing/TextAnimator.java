package com.scrimmy.client.typing;

import com.scrimmy.client.module.visual.TypingAnimation;
import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.render.ClientFonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

import java.util.Arrays;
import java.util.Locale;
import java.util.WeakHashMap;

/** Render-only state. No references back to the weak keys, and no writes to an input model. */
public final class TextAnimator {
    private static final WeakHashMap<Object, State[]> FIELDS = new WeakHashMap<>();
    private static final State[] MASKED_FIELD = new State[0];
    private static int fontRevision = -1;
    private static String selectedFont;
    private static final long GHOST_NS = 85_000_000L;
    private TextAnimator() {}

    public static void clear() { FIELDS.clear(); }

    public static State begin(Object field, int lane, String value, boolean sign, boolean focused, String label) {
        TypingAnimation module = TypingAnimation.current();
        var screen = Minecraft.getInstance().screen;
        boolean book = screen instanceof BookEditScreen || screen instanceof BookSignScreen;
        boolean active = module != null && module.enabled()
                && (sign || book ? module.signsAndBooks.get() : screen instanceof ChatScreen ? module.chat.get() : module.otherFields.get());
        if (!active) { FIELDS.remove(field); return null; }
        // The client widget font keeps its identity while its selected face/atlas changes.
        int revision = ClientFonts.revision();
        String selected = ScrimmyClient.THEME.font.get();
        if (revision != fontRevision || !selected.equals(selectedFont)) {
            clear(); fontRevision = revision; selectedFont = selected;
        }
        State[] lanes = FIELDS.get(field);
        if (lanes == MASKED_FIELD) return null;
        if (lanes == null) {
            String name = (field.getClass().getName() + " " + label).toLowerCase(Locale.ROOT);
            lanes = new State[4];
            // Vanilla has no password widget. Protect common mod subclasses/labels as well as
            // formatters that substitute bullets (detected by the glyph validation below).
            if (name.contains("password") || name.contains("passwd") || name.contains("masked")) {
                FIELDS.put(field, MASKED_FIELD);
                return null;
            }
            FIELDS.put(field, lanes);
        }
        State state = lanes[lane];
        if (state == null) lanes[lane] = state = new State();
        long now = System.nanoTime();
        state.update(value, now, module.speed.get().longValue() * 1_000_000L);
        state.dt = state.frameTime == 0 ? 1 : Math.min(.05, (now - state.frameTime) / 1e9);
        state.frameTime = now;
        state.now = now;
        state.focused = focused;
        state.smooth = module.smoothCaret.get();
        state.style = module.style.get();
        state.masked = false;
        state.selectedThisFrame = false;
        state.selectionDrawn = 0;
        state.frame++;
        return state;
    }

    public static boolean draw(State s, GuiGraphics g, Font font, FormattedCharSequence text,
                               int start, int x, int y, int color, boolean shadow) {
        if (s == null || font.isBidirectional() || s.masked) return false;
        s.prepare(font, start);
        text.accept(s);
        if (!s.valid) { s.masked = true; s.ghostCount = 0; return false; }
        return s.render(g, x, y, color, shadow);
    }

    public static boolean draw(State s, GuiGraphics g, Font font, String text,
                               int start, int x, int y, int color, boolean shadow) {
        if (s == null || font.isBidirectional() || s.masked) return false;
        s.prepare(font, start);
        StringDecomposer.iterateFormatted(text, Style.EMPTY, s);
        if (!s.valid) { s.masked = true; s.ghostCount = 0; return false; }
        return s.render(g, x, y, color, shadow);
    }

    /** A range width without substring allocation, using the same styled glyph advances as drawing. */
    public static float width(State s, Font font, int from, int to) {
        if (s == null) return 0;
        float width = 0;
        for (int i = Math.max(0, from); i < Math.min(to, s.previous.length());) {
            int cp = s.previous.codePointAt(i);
            Glyph glyph = s.glyphs[i];
            if (glyph == null && cp == '\u00a7' && i + 1 < to) { i += 2; continue; }
            if (glyph == null) {
                glyph = s.glyphs[i] = new Glyph(cp, Style.EMPTY);
                glyph.advance = font.getSplitter().stringWidth(glyph);
            }
            width += glyph.advance;
            i += Character.charCount(cp);
        }
        return width;
    }

    public static boolean smooth(State s) { return s != null && s.smooth && !s.masked; }

    /** Called at the native caret location even during vanilla's off half of the blink. */
    public static void caret(State s, GuiGraphics g, Font font, float x, float y, int color,
                             boolean append, boolean shadow, boolean visible) {
        if (!smooth(s)) return;
        if (!visible || !s.focused) { s.caretReady = false; return; }
        if (!s.caretReady || Math.abs(y - s.caretY) > font.lineHeight * 2) {
            s.caretX = x; s.caretY = y; s.caretReady = true;
        }
        float ease = (float)(1 - Math.exp(-25 * s.dt));
        s.caretX += (x - s.caretX) * ease;
        s.caretY += (y - s.caretY) * ease;
        // A continuous cosine keeps the caret visible at low opacity in the off phase.
        float blink = .25f + .75f * (float)(.5 + .5 * Math.cos((s.now - s.lastEdit) / 1e9 * Math.PI / .3));
        if (s.now - s.lastEdit < 180_000_000L) blink = 1;
        int tint = alpha(color, blink);
        g.pose().pushMatrix();
        g.pose().translate(s.caretX, s.caretY);
        if (append) g.drawString(font, "_", 0, 0, tint, shadow);
        else g.fill(0, -1, 1, font.lineHeight + 1, tint);
        g.pose().popMatrix();
    }

    /** Keep vanilla selection geometry and inverted text, easing the reveal with a scissor. */
    public static boolean selection(State s, GuiGraphics g, int x1, int y1, int x2, int y2, boolean invert) {
        if (!smooth(s)) return false;
        if (!s.selectedThisFrame) s.selectionAlpha += (1 - s.selectionAlpha) * (float)(1 - Math.exp(-30 * s.dt));
        s.selectedThisFrame = true;
        if (s.selectionDrawn == s.selections.length) s.selections = Arrays.copyOf(s.selections, s.selections.length * 2);
        Selection rect = s.selections[s.selectionDrawn];
        if (rect == null) rect = s.selections[s.selectionDrawn] = new Selection();
        s.selectionDrawn++;
        rect.x1 = x1; rect.y1 = y1; rect.x2 = x2; rect.y2 = y2; rect.invert = invert;
        revealSelection(s, g, x1, y1, x2, y2, invert);
        return true;
    }

    private static void revealSelection(State s, GuiGraphics g, int x1, int y1, int x2, int y2, boolean invert) {
        int left = Math.min(x1, x2), right = Math.max(x1, x2);
        int bottom = Math.max(y1, y2), top = Math.min(y1, y2);
        int edge = left + Math.round((right - left) * s.selectionAlpha);
        if (edge <= left) return;
        g.enableScissor(left, top, edge, bottom);
        g.textHighlight(x1, y1, x2, y2, invert);
        g.disableScissor();
    }

    public static void finish(State s, GuiGraphics g) {
        if (s == null || s.masked) return;
        for (int i = 0; i < s.ghostCount;) {
            Ghost ghost = s.ghosts[i];
            float t = (s.now - ghost.born) / (float)GHOST_NS;
            if (t >= 1) {
                s.ghosts[i] = s.ghosts[--s.ghostCount]; s.ghosts[s.ghostCount] = null;
            } else {
                drawGlyph(g, ghost.font, ghost.glyph, ghost.x, ghost.y, alpha(ghost.color, (1 - t) * (1 - t)), ghost.shadow, ghost.scale);
                i++;
            }
        }
        if (smooth(s) && !s.selectedThisFrame && s.selectionAlpha > .01f) {
            s.selectionAlpha *= (float)Math.exp(-35 * s.dt);
            for (int i = 0; i < s.selectionCount; i++) {
                Selection rect = s.selections[i];
                revealSelection(s, g, rect.x1, rect.y1, rect.x2, rect.y2, rect.invert);
            }
        } else if (s.selectedThisFrame) {
            s.selectionCount = s.selectionDrawn;
        }
    }

    private static int alpha(int color, float amount) {
        // All vanilla input colors in 1.21.11 are ARGB, including black book text.
        return (color & 0x00ffffff) | Math.max(0, Math.min(255, Math.round((color >>> 24) * amount))) << 24;
    }

    private static void drawGlyph(GuiGraphics g, Font font, Glyph glyph, float x, float y, int color, boolean shadow, float scale) {
        if ((color >>> 24) == 0) return;
        g.pose().pushMatrix();
        g.pose().translate(x + glyph.advance * .5f, y + font.lineHeight * .5f);
        g.pose().scale(scale, scale);
        g.pose().translate(-glyph.advance * .5f, -font.lineHeight * .5f);
        g.drawString(font, glyph, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    public static final class State implements FormattedCharSink {
        String previous;
        long[] born = new long[0], duration = new long[0];
        boolean[] pasted = new boolean[0];
        Glyph[] glyphs = new Glyph[0];
        Ghost[] ghosts = new Ghost[16];
        Selection[] selections = new Selection[4];
        int ghostCount, frame, start, end, cursor;
        long now, lastEdit, frameTime;
        double dt;
        boolean valid, masked, focused, smooth, selectedThisFrame, caretReady;
        String style;
        Font font;
        float caretX, caretY, selectionAlpha;
        int selectionCount, selectionDrawn;

        // Edits allocate only when the value changes. Timestamps are retained through prefix/suffix edits.
        void update(String value, long time, long speed) {
            if (value.equals(previous)) return;
            if (previous == null) {
                previous = value; born = new long[value.length()]; duration = new long[value.length()];
                glyphs = new Glyph[value.length()]; pasted = new boolean[value.length()]; lastEdit = time; return;
            }
            int prefix = 0, oldLength = previous.length(), length = value.length();
            while (prefix < oldLength && prefix < length && previous.charAt(prefix) == value.charAt(prefix)) prefix++;
            // Never split a surrogate pair at either edge of a replacement.
            if (prefix > 0 && prefix < oldLength && Character.isLowSurrogate(previous.charAt(prefix))) prefix--;
            int suffix = 0;
            while (suffix < oldLength - prefix && suffix < length - prefix
                    && previous.charAt(oldLength - suffix - 1) == value.charAt(length - suffix - 1)) suffix++;
            if (suffix > 0 && Character.isLowSurrogate(previous.charAt(oldLength - suffix))) suffix--;
            for (int i = prefix; i < oldLength - suffix; i++) {
                Glyph glyph = glyphs[i];
                if (glyph != null && glyph.drawn == frame) {
                    if (ghostCount == ghosts.length) ghosts = Arrays.copyOf(ghosts, ghosts.length * 2);
                    ghosts[ghostCount++] = new Ghost(glyph, time);
                }
            }
            long[] nextBorn = new long[length], nextDuration = new long[length];
            Glyph[] nextGlyphs = new Glyph[length];
            boolean[] nextPasted = new boolean[length];
            System.arraycopy(born, 0, nextBorn, 0, prefix);
            System.arraycopy(duration, 0, nextDuration, 0, prefix);
            System.arraycopy(glyphs, 0, nextGlyphs, 0, prefix);
            System.arraycopy(pasted, 0, nextPasted, 0, prefix);
            System.arraycopy(born, oldLength - suffix, nextBorn, length - suffix, suffix);
            System.arraycopy(duration, oldLength - suffix, nextDuration, length - suffix, suffix);
            System.arraycopy(glyphs, oldLength - suffix, nextGlyphs, length - suffix, suffix);
            System.arraycopy(pasted, oldLength - suffix, nextPasted, length - suffix, suffix);
            long interval = time - lastEdit;
            long effective = Math.min(speed, Math.max(45_000_000L, interval * 2));
            int added = value.codePointCount(prefix, length - suffix);
            // A paste uses one timestamp/duration for the entire block. There is no stagger or queue.
            Arrays.fill(nextBorn, prefix, length - suffix, time);
            Arrays.fill(nextPasted, prefix, length - suffix, added > 3);
            Arrays.fill(nextDuration, prefix, length - suffix, effective);
            if (added <= 3 && interval < speed) {
                for (int i = 0; i < nextBorn.length; i++) {
                    if (nextBorn[i] != 0) nextDuration[i] = Math.min(nextDuration[i], effective);
                }
            }
            previous = value; born = nextBorn; duration = nextDuration; glyphs = nextGlyphs; pasted = nextPasted; lastEdit = time;
        }

        void prepare(Font font, int start) {
            this.font = font; this.start = start; this.cursor = start; this.end = start; valid = true;
        }

        @Override public boolean accept(int ignoredIndex, Style style, int cp) {
            // Validate actual displayed glyphs against real input. Masked/reordered/custom substitutions
            // retain their complete vanilla draw, and can never reveal cached unmasked input.
            while (cursor + 1 < previous.length() && previous.charAt(cursor) == '\u00a7' && previous.codePointAt(cursor) != cp) cursor += 2;
            if (cursor >= previous.length() || previous.codePointAt(cursor) != cp) { valid = false; return false; }
            Glyph glyph = glyphs[cursor];
            if (glyph == null) glyph = glyphs[cursor] = new Glyph(cp, style);
            if (!style.equals(glyph.style) || glyph.font != font) {
                glyph.style = style; glyph.font = font;
                glyph.advance = font.getSplitter().stringWidth(glyph);
            }
            cursor += Character.charCount(cp); end = cursor;
            return true;
        }

        boolean render(GuiGraphics g, int x, int y, int color, boolean shadow) {
            boolean animating = false;
            float offset = 0;
            // Keep removal positions current, but let a settled run use its original batched draw.
            for (int i = start; i < end;) {
                Glyph glyph = glyphs[i];
                if (glyph == null) { i++; continue; }
                glyph.x = x + offset; glyph.y = y; glyph.shadow = shadow; glyph.drawn = frame;
                glyph.presentedY = y; glyph.presentedColor = color; glyph.scale = 1;
                if (born[i] != 0 && now - born[i] < duration[i]) animating = true;
                offset += glyph.advance;
                i += Character.charCount(glyph.cp);
            }
            if (!animating) return false;
            for (int i = start; i < end;) {
                Glyph glyph = glyphs[i];
                if (glyph == null) { i++; continue; }
                float t = born[i] == 0 ? 1 : Math.min(1, (now - born[i]) / (float)Math.max(1, duration[i]));
                float eased = 1 - (1 - t) * (1 - t) * (1 - t);
                float dy = !pasted[i] && "Slide".equals(style) ? (1 - eased) * 3 : 0;
                float scale = 1;
                if (!pasted[i] && "Pop".equals(style)) {
                    float u = t - 1;
                    scale = .6f + .4f * (1 + 2.70158f * u * u * u + 1.70158f * u * u);
                }
                glyph.presentedY = glyph.y + dy; glyph.presentedColor = alpha(color, eased); glyph.scale = scale;
                drawGlyph(g, font, glyph, glyph.x, glyph.presentedY, glyph.presentedColor, shadow, scale);
                i += Character.charCount(glyph.cp);
            }
            return true;
        }
    }

    private static final class Glyph implements FormattedCharSequence {
        final int cp;
        Style style;
        Font font;
        float advance, x, y, presentedY, scale = 1;
        int presentedColor, drawn;
        boolean shadow;
        Glyph(int cp, Style style) { this.cp = cp; this.style = style; }
        @Override public boolean accept(FormattedCharSink sink) { return sink.accept(0, style, cp); }
    }

    private static final class Selection {
        int x1, y1, x2, y2;
        boolean invert;
    }

    private static final class Ghost {
        final Glyph glyph;
        final Font font;
        final float x, y, scale;
        final int color;
        final boolean shadow;
        final long born;
        Ghost(Glyph source, long born) {
            glyph = new Glyph(source.cp, source.style); glyph.advance = source.advance;
            font = source.font; x = source.x; y = source.presentedY; color = source.presentedColor;
            scale = source.scale; shadow = source.shadow; this.born = born;
        }
    }
}
