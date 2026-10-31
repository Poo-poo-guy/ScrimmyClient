package com.scrimmy.client.gui;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.DetectionLog.Detection;
import com.scrimmy.client.basefinding.GeodeScoring;
import com.scrimmy.client.module.basefinding.Basefinding;
import com.scrimmy.client.render.PanelBlur;
import com.scrimmy.client.render.RoundedRenderer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.*;

/** Shared Basefinding log: uses the client's glass/blur renderer and alpha animation. */
public final class BasefindingLogScreen extends Screen {
    private final Basefinding module;
    private final Screen parent;
    private final PanelBlur blur = new PanelBlur();
    private List<Detection> entries = List.of();
    private EditBox notes;
    private Long selected;
    private int page, previousBlur = -1;
    private float alpha;
    private long lastFrame = System.nanoTime();
    private boolean updatingNotes;

    public BasefindingLogScreen(Basefinding module, Screen parent) {
        super(Component.literal("Basefinding detection log"));
        this.module = module;
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return Math.max(8, (width - 700) / 2);
    }

    private int panelWidth() {
        return Math.min(700, width - 16);
    }

    private int rows() {
        return Math.max(1, (height - 205) / 32);
    }

    @Override
    public void added() {
        previousBlur = minecraft.options.getMenuBackgroundBlurriness();
        minecraft.options.menuBackgroundBlurriness().set(ScrimmyClient.THEME.blur.get().intValue());
    }

    @Override
    protected void init() {
        notes =
                new EditBox(
                        font,
                        left() + 12,
                        height - 106,
                        Math.max(40, panelWidth() - 24),
                        20,
                        Component.literal("Detection notes"));
        notes.setMaxLength(4096);
        notes.setResponder(
                text -> {
                    Detection d = selection();
                    if (!updatingNotes && d != null)
                        module.annotate(d.key(), text, d.checked(), d.falsePositive());
                });
        addRenderableWidget(notes);
        reload();
        select(selected);
    }

    @Override
    public void tick() {
        reload();
    }

    private void reload() {
        entries =
                module.detections().stream()
                        .sorted(Comparator.comparingLong(Detection::lastSeen).reversed())
                        .toList();
        page = Math.min(page, Math.max(0, (entries.size() - 1) / rows()));
    }

    private Detection selection() {
        if (selected != null) for (Detection d : entries) if (d.key() == selected) return d;
        return null;
    }

    private void select(Long key) {
        selected = key;
        Detection d = selection();
        updatingNotes = true;
        notes.setValue(d == null ? "" : d.notes());
        updatingNotes = false;
        notes.active = d != null && module.ready();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (ScrimmyClient.THEME.blur.get() >= 1) blur.capture(minecraft);
        else blur.close();
        g.fill(0, 0, width, height, 0x44081018);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.nanoTime();
        alpha +=
                (1 - alpha)
                        * (float)
                                (1
                                        - Math.exp(
                                                -Math.min(.1, (now - lastFrame) / 1_000_000_000.0)
                                                        * 12));
        lastFrame = now;
        int x = left(), w = panelWidth(), accent = ScrimmyClient.THEME.accent.get();
        if (blur.texture() != null)
            RoundedRenderer.glass(g, x, 16, w, height - 32, 10, fade(0xffffffff), blur.texture());
        RoundedRenderer.panel(
                g, x, 16, w, height - 32, 10, fade(0xe01b2c3a), fade(0x668da9bc), .2f, .5f);
        text(g, "Basefinding / ChunkFinder log", x + 12, 27, accent);
        text(
                g,
                module.ready()
                        ? entries.size() + " saved locations · select a row to manage it"
                        : "Join an Overworld and enable Basefinding to load detections",
                x + 12,
                43,
                0xffa9bdcc);
        g.enableScissor(x + 8, 59, x + w - 8, height - 143);
        for (int i = 0; i < rows(); i++) {
            int index = page * rows() + i;
            if (index >= entries.size()) break;
            Detection d = entries.get(index);
            int y = 61 + i * 32;
            boolean chosen = selected != null && d.key() == selected;
            RoundedRenderer.panel(
                    g,
                    x + 10,
                    y,
                    w - 20,
                    29,
                    5,
                    fade(chosen ? 0x8859788d : 0x333e586c),
                    fade(0x228da9bc),
                    0,
                    0);
            String status =
                    d.falsePositive()
                            ? "False positive"
                            : d.checked() ? "Checked" : GeodeScoring.confidence(d.score());
            text(
                    g,
                    "Chunk "
                            + d.chunkX()
                            + ", "
                            + d.chunkZ()
                            + " · "
                            + Math.round(d.score())
                            + "/100 · "
                            + status,
                    x + 17,
                    y + 4,
                    chosen ? accent : 0xffedf6ff);
            text(
                    g,
                    d.coordinates()
                            + " · "
                            + d.clusters()
                            + " clusters · seen "
                            + Instant.ofEpochMilli(d.lastSeen()),
                    x + 17,
                    y + 16,
                    0xffa9bdcc);
        }
        g.disableScissor();
        text(g, "Notes for selected location", x + 12, height - 120, 0xffa9bdcc);
        button(g, "Copy coords", 0, height - 78, mx, my);
        button(g, "Clear one", 1, height - 78, mx, my);
        button(g, "Checked", 2, height - 78, mx, my);
        button(g, "False positive", 3, height - 78, mx, my);
        button(g, "Clear all", 0, height - 49, mx, my);
        button(g, "Export", 1, height - 49, mx, my);
        button(g, "Previous", 2, height - 49, mx, my);
        button(g, "Next", 3, height - 49, mx, my);
        text(g, "Page " + (page + 1) + " · Esc to return", x + w - 160, 27, 0xffa9bdcc);
        super.render(g, mx, my, delta);
    }

    private int buttonX(int column) {
        return left() + 12 + column * (panelWidth() - 24) / 4;
    }

    private int buttonWidth() {
        return (panelWidth() - 24) / 4 - 4;
    }

    private void button(GuiGraphics g, String label, int column, int y, int mx, int my) {
        int x = buttonX(column), w = buttonWidth();
        boolean hovered = mx >= x && mx < x + w && my >= y && my < y + 23;
        RoundedRenderer.panel(
                g, x, y, w, 23, 5, fade(hovered ? 0x99648395 : 0x55557183), fade(0x338da9bc), 0, 0);
        g.enableScissor(x + 3, y, x + w - 3, y + 23);
        text(g, label, x + 6, y + 8, 0xffedf6ff);
        g.disableScissor();
    }

    private void text(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(font, text, x, y, fade(color), false);
    }

    private int fade(int color) {
        return ((int) ((color >>> 24) * alpha) << 24) | (color & 0xffffff);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        int x = left();
        if (mx >= x + 10
                && mx < x + panelWidth() - 10
                && my >= 61
                && my < 61 + rows() * 32
                && my < height - 143) {
            int index = page * rows() + ((int) my - 61) / 32;
            if (index < entries.size()) {
                select(entries.get(index).key());
                return true;
            }
        }
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 4; col++) {
                int bx = buttonX(col), by = height - (row == 0 ? 78 : 49);
                if (mx < bx || mx >= bx + buttonWidth() || my < by || my >= by + 23) continue;
                Detection d = selection();
                if (!module.ready()) return true;
                if (row == 0 && d != null)
                    switch (col) {
                        case 0 -> minecraft.keyboardHandler.setClipboard(d.coordinates());
                        case 1 -> {
                            module.remove(d.key());
                            reload();
                            select(null);
                        }
                        case 2 -> {
                            module.annotate(d.key(), d.notes(), !d.checked(), d.falsePositive());
                            reload();
                        }
                        case 3 -> {
                            module.annotate(d.key(), d.notes(), d.checked(), !d.falsePositive());
                            reload();
                        }
                    }
                if (row == 1)
                    switch (col) {
                        case 0 -> {
                            module.clearDetections();
                            reload();
                            select(null);
                        }
                        case 1 -> module.export();
                        case 2 -> page = Math.max(0, page - 1);
                        case 3 ->
                                page =
                                        Math.min(
                                                Math.max(0, (entries.size() - 1) / rows()),
                                                page + 1);
                    }
                return true;
            }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        blur.close();
        if (previousBlur >= 0) minecraft.options.menuBackgroundBlurriness().set(previousBlur);
    }
}
