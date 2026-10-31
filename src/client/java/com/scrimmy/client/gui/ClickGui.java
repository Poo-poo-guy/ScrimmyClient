package com.scrimmy.client.gui;

import com.scrimmy.client.config.ConfigStore;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.module.ModuleManager;
import com.scrimmy.client.module.visual.HitParticles;
import com.scrimmy.client.render.PanelBlur;
import com.scrimmy.client.render.RoundedRenderer;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.theme.PanelState;
import com.scrimmy.client.theme.Theme;
import com.scrimmy.client.util.GuiTransform;
import com.scrimmy.client.util.PanelLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ClickGui extends Screen {
    private static final int HEADER = 30, ROW = 36, SETTING = 34, COLOR = 120;
    private static final String APPEARANCE = "ScrimmyClient+";
    private static final int TEXT = 0xffe5f1f8, MUTED = 0xff9cb3c2;
    private static final int[] PRESETS = {0xff70d6db, 0xffb693ee, 0xfff494a5, 0xffffcf78};
    private final Theme theme;
    private final ModuleManager modules;
    private final ConfigStore config;
    private final List<Panel> panels = new ArrayList<>();
    private final float[] rowHeights = new float[Category.values().length];
    private final PanelBlur blur = new PanelBlur();
    private final Visual appearance = new Visual();
    private final Map<Module, Visual> visuals = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> fills = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> toggles = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> hovers = new IdentityHashMap<>();
    private final Map<NumberSetting, Double> labelValues = new IdentityHashMap<>();
    private final Map<NumberSetting, String> labels = new IdentityHashMap<>();
    private final Map<Setting<?>, Boolean> expandedControls = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> controlExpansion = new IdentityHashMap<>();
    private final Map<GroupSetting, Float> groupExpansion = new IdentityHashMap<>();
    private final Map<GradientSetting, Integer> gradientSelection = new IdentityHashMap<>();
    private final Map<BlockListSetting, String> blockSelection = new IdentityHashMap<>();
    private BlockListSetting editingBlocks;
    private String blockInput = "", blockStatus = "Enter ID to add; click x to remove";
    private boolean gradientPosition, scrolling;
    private final float[] hsb = new float[3];
    private long lastFrame = System.nanoTime();
    private float open, dt = 1f / 60f, scale;
    private boolean closing;
    private Panel draggedPanel, activePanel;
    private float dragOffsetX, dragOffsetY;
    private Setting<?> activeSetting;
    private GuiTransform.Track activeTrack;
    private int activeColorChannel = -1;
    private Module bindingModule;
    private boolean bindingGui;
    private GuiTransform transform;
    private int previousGameBlur = -1, drawnAccent;
    private String tooltip;
    private float tooltipTime;
    private String lastTooltip;

    public ClickGui(Theme theme, ModuleManager modules, ConfigStore config) {
        super(Component.literal("Scrimmy Client"));
        this.theme = theme; this.modules = modules; this.config = config;
        scale = theme.guiScale.get().floatValue();
        transform = new GuiTransform(scale, minecraft.getWindow().getGuiScale());
        drawnAccent = theme.accent.get();
        for (Category category : Category.values()) panels.add(new Panel(category));
        modules.all().forEach(m -> visuals.put(m, new Visual()));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (draggedPanel != null) draggedPanel.persistPosition();
        closing = true; activeSetting = null; activeTrack = null; draggedPanel = null; activePanel = null; scrolling = false;
    }
    @Override public void added() {
        previousGameBlur = minecraft.options.getMenuBackgroundBlurriness();
        updateBlur();
    }
    @Override public void removed() {
        if (draggedPanel != null) draggedPanel.persistPosition();
        blur.close();
        if (previousGameBlur >= 0) minecraft.options.menuBackgroundBlurriness().set(previousGameBlur);
        config.save();
    }
    @Override public void resize(int width, int height) {
        if (draggedPanel != null) draggedPanel.persistPosition();
        draggedPanel = null; activePanel = null; activeSetting = null; activeTrack = null; scrolling = false;
        super.resize(width, height);
    }
    private void updateBlur() {
        int wanted = theme.blur.get().intValue();
        if (minecraft.options.getMenuBackgroundBlurriness() != wanted)
            minecraft.options.menuBackgroundBlurriness().set(wanted);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        updateBlur();
        if (theme.blur.get() >= 1) blur.capture(minecraft);
        else blur.close();
        g.fill(0, 0, width, height, fade(0x22081018));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        long now = System.nanoTime();
        dt = Math.min(0.1f, Math.max(0, (now - lastFrame) / 1_000_000_000f));
        lastFrame = now;
        open = animate(open, closing ? 0 : 1);
        scale = animate(scale, theme.guiScale.get().floatValue());
        transform = new GuiTransform(scale, minecraft.getWindow().getGuiScale());
        drawnAccent = blend(drawnAccent, theme.accent.get(), response(14));
        if (closing && open < 0.02f) { minecraft.setScreen(null); return; }
        // Mouse events use fractional GUI coordinates; keep hover in that same space.
        float mx = transform.local(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()));
        float my = transform.local(minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
        int logicalWidth = Math.max(1, (int)(width / scale));
        int logicalHeight = Math.max(1, (int)(height / scale));
        // Width adapts to the available GUI viewport; the scale multiplier stays exact.
        int gap = logicalWidth < 400 ? 4 : 8;
        PanelLayout layout = PanelLayout.fit(logicalWidth, logicalHeight, theme.panelWidth.get().intValue(), panels.size(), gap);
        for (Module module : modules.all()) {
            for (Setting<?> setting : module.settings()) animateSetting(setting);
            Visual v = visuals.get(module);
            v.expansion = animate(v.expansion, v.expanded ? 1 : 0);
            v.toggle = animate(v.toggle, module.enabled() ? 1 : 0);
        }
        for (Setting<?> setting : theme.settings()) animateSetting(setting);
        appearance.expansion = animate(appearance.expansion, appearance.expanded ? 1 : 0);
        java.util.Arrays.fill(rowHeights, HEADER);
        for (Panel panel : panels) if (panel.state.automatic()) {
            float wanted = panel.state.open() ? Math.min(HEADER + contentHeight(panel) + 9, layout.cellHeight()) : HEADER;
            int row = panel.category.ordinal() / layout.columns();
            rowHeights[row] = Math.max(rowHeights[row], Math.min(layout.cellHeight(), animate(panel.height, wanted)));
        }
        for (Panel panel : panels) {
            float autoY = 4;
            for (int row = 0; row < panel.category.ordinal() / layout.columns(); row++) autoY += rowHeights[row] + gap;
            panel.layout(layout, autoY, contentHeight(panel), logicalWidth, logicalHeight);
        }
        Panel hovered = panelAt(mx, my);
        tooltip = null;
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        for (Panel panel : panels) {
            // Each panel owns a stratum, so text and controls cannot interleave with another panel.
            g.nextStratum();
            renderPanel(g, panel, hovered == panel ? mx : -10000, hovered == panel ? my : -10000);
        }
        renderTooltip(g, mx, my, logicalWidth, logicalHeight);
        g.pose().popMatrix();
    }

    private void renderPanel(GuiGraphics g, Panel p, float mx, float my) {
        int x = p.drawX(), y = p.drawY(), w = p.width, h = p.drawHeight();
        int radius = theme.radius.get().intValue();
        int fill = ((int)(theme.opacity.get() * 255) << 24) | (theme.glassTint.get() & 0xffffff);
        if (blur.texture() != null)
            RoundedRenderer.glass(g, x, y, w, h, radius, fade(0xffffffff), blur.texture());
        RoundedRenderer.panel(g, x, y, w, h, radius, fade(fill), fade(0x668da9bc),
            theme.glow.get().floatValue() * open, theme.shadow.get().floatValue() * open);
        p.headerHover = animate(p.headerHover, inside(mx, my, x, y, w, HEADER) ? 1 : 0);
        rounded(g, x + 3, y + 3, w - 6, HEADER - 6, Math.min(radius, 8), ((int)(12 + 14 * p.headerHover) << 24) | 0xffffff);
        int titlePadding = w < 90 ? 4 : 9;
        if (p.category == Category.BASEFINDING && font.width(p.category.title()) > w - 8) {
            draw(g, p.category.title().substring(0, 4), x + 5, y + 5, TEXT);
            draw(g, p.category.title().substring(4), x + 5, y + 17, TEXT);
        } else {
            drawClipped(g, p.category.title(), x + titlePadding, y + 11, w - titlePadding - (w < 90 ? 3 : 22), TEXT);
        }
        if (w >= 90) draw(g, p.state.open() ? "-" : "+", x + w - 15, y + 11, MUTED);
        int top = y + HEADER + 3, bottom = y + h - Math.max(5, radius / 2);
        if (bottom <= top) return;
        rounded(g, x + 8, y + HEADER - 1, w - 16, 1, 0, 0x33ffffff);
        // The scissor stays inside the rounded corners; no content crosses their mask.
        g.enableScissor(x + 5, top, x + w - 5, bottom);
        int cursor = Math.round(top - p.scroll);
        if (modules.in(p.category).isEmpty() && p.category != Category.CLIENT) {
            drawClipped(g, "No modules yet", x + 10, cursor + 10, w - 20, MUTED);
        }
        for (Module module : modules.in(p.category)) {
            Visual v = visuals.get(module);
            renderModuleRow(g, module, v, x, cursor, w, mx, my);
            cursor += ROW;
            int expanded = Math.round(moduleSettingsHeight(module) * v.expansion);
            if (expanded > 0 && cursor < bottom && cursor + expanded > top) {
                g.enableScissor(x + 7, Math.max(top, cursor), x + w - 7, Math.min(cursor + expanded, bottom));
                int sy = cursor + 4;
                for (Setting<?> setting : module.settings()) {
                    renderSetting(g, setting, x + 9, sy, w - 18, mx, my);
                    sy += settingHeight(setting);
                }
                renderKeybind(g, module, x + 9, sy, w - 18);
                g.disableScissor();
            }
            cursor += expanded;
        }
        if (p.category == Category.CLIENT) {
            appearance.hover = animate(appearance.hover, inside(mx, my, x + 7, cursor, w - 14, ROW) ? 1 : 0);
            rounded(g, x + 7, cursor + 2, w - 14, ROW - 4, 6, ((int)(25 + appearance.hover * 22) << 24) | 0xffffff);
            if (font.width(APPEARANCE) <= w - 14) {
                draw(g, APPEARANCE, x + 7, cursor + 12, drawnAccent);
            } else {
                // Wrap the name on very narrow viewports without shrinking the font.
                drawClipped(g, APPEARANCE.substring(0, 7), x + 5, cursor + 5, w - 10, drawnAccent);
                drawClipped(g, APPEARANCE.substring(7), x + 5, cursor + 20, w - 10, drawnAccent);
            }
            if (inside(mx, my, x + 7, cursor, w - 14, ROW)) tooltip = APPEARANCE;
            cursor += ROW;
            int expanded = Math.round(appearanceHeight() * appearance.expansion);
            if (expanded > 0 && cursor < bottom && cursor + expanded > top) {
                g.enableScissor(x + 7, Math.max(top, cursor), x + w - 7, Math.min(cursor + expanded, bottom));
                renderAppearance(g, x + 9, cursor + 4, w - 18, mx, my);
                g.disableScissor();
            }
        }
        g.disableScissor();
        if (p.maxScroll() > 0) {
            int trackHeight = bottom - top;
            int thumb = Math.max(12, Math.round(trackHeight * Math.min(1, trackHeight / (float)Math.max(1, p.content))));
            int thumbY = top + Math.round((trackHeight - thumb) * p.scroll / p.maxScroll());
            rounded(g, x + w - 5, top, 2, trackHeight, 1, 0x335f7382);
            rounded(g, x + w - 5, thumbY, 2, thumb, 1, 0xcc9cb3c2);
        }
    }

    private void renderModuleRow(GuiGraphics g, Module module, Visual v, int x, int y, int w, float mx, float my) {
        v.hover = animate(v.hover, inside(mx, my, x + 7, y, w - 14, ROW) && g.containsPointInScissor((int)(mx * scale), (int)(my * scale)) ? 1 : 0);
        rounded(g, x + 7, y + 2, w - 14, ROW - 4, 6, ((int)(24 + 24 * v.hover) << 24) | 0xffffff);
        boolean narrow = w < 125;
        drawClipped(g, module.name(), x + (narrow ? 8 : 12), y + (narrow ? 5 : 12), w - (narrow ? 16 : 65), TEXT);
        int sx = narrow ? x + 12 : x + w - 43, sy = y + (narrow ? 19 : 11);
        rounded(g, sx, sy, 24, 12, 6, blend(0xff435565, drawnAccent, v.toggle));
        rounded(g, sx + 2 + (int)(12 * v.toggle), sy + 2, 8, 8, 4, 0xfff5ffff);
        if (narrow) draw(g, v.expanded ? "-" : "+", x + w - 18, y + 20, MUTED);
        if (inside(mx, my, x + 7, y, w - 14, ROW) && g.containsPointInScissor((int)(mx * scale), (int)(my * scale))) tooltip = module.description();
    }

    private void renderAppearance(GuiGraphics g, int x, int y, int w, float mx, float my) {
        for (Setting<?> setting : theme.settings()) {
            renderSetting(g, setting, x, y, w, mx, my);
            y += settingHeight(setting);
        }
        rounded(g, x, y + 2, w, 29, 6, 0x55557183);
        drawClipped(g, bindingGui ? "Press a key..." : "GUI key: " + keyName(config.guiKey()), x + 7, y + 12, w - 14, TEXT);
        y += 35;
        rounded(g, x, y + 2, w, 29, 6, 0x557c5760);
        drawClipped(g, "Reset to defaults", x + 7, y + 12, w - 14, TEXT);
    }

    private void renderKeybind(GuiGraphics g, Module module, int x, int y, int w) {
        rounded(g, x, y + 2, w, 28, 6, 0x55557183);
        drawClipped(g, bindingModule == module ? "Press a key (Esc clears)" : "Key: " + keyName(module.keybind()), x + 6, y + 11, w - 12, TEXT);
    }

    private void animateSetting(Setting<?> setting) {
        if (setting instanceof GroupSetting group)
            groupExpansion.put(group, animate(groupExpansion.getOrDefault(group, group.get() ? 1f : 0f), group.get() ? 1 : 0));
        if (setting instanceof ModeSetting || setting instanceof MultiSelectSetting || setting instanceof GradientSetting || setting instanceof BlockListSetting)
            controlExpansion.put(setting, animate(controlExpansion.getOrDefault(setting, 0f), expandedControls.getOrDefault(setting, false) ? 1 : 0));
    }

    private void renderSetting(GuiGraphics g, Setting<?> setting, int x, int y, int w, float mx, float my) {
        int h = settingHeight(setting);
        if (h <= 0) return;
        g.enableScissor(x, y, x + w, y + h);
        boolean hovered = inside(mx, my, x, y, w, h) && g.containsPointInScissor((int)(mx * scale), (int)(my * scale));
        float hover = animate(hovers.getOrDefault(setting, 0f), hovered ? 1 : 0);
        hovers.put(setting, hover);
        if (hovered) tooltip = setting.tooltip();
        if (hover > 0.01f) rounded(g, x, y + 1, w, h - 2, 5, ((int)(22 * hover) << 24) | 0xffffff);
        if (setting instanceof GroupSetting group) {
            rounded(g, x, y + 3, w, SETTING - 6, 5, 0x335f7c91);
            drawClipped(g, group.label(), x + 6, y + 12, w - 24, drawnAccent);
            draw(g, group.get() ? "-" : "+", x + w - 15, y + 12, MUTED);
        } else if (setting instanceof ActionSetting action) {
            rounded(g, x, y + 3, w, SETTING - 6, 5, 0x55557183);
            drawClipped(g, action.label(), x + 6, y + 12, w - 12, TEXT);
            if (hovered && action.key().endsWith("Preset")) {
                HitParticles hit = modules.find(HitParticles.class);
                if (hit != null) tooltip = hit.presetStatus();
            }
        } else if (setting instanceof BooleanSetting bool) {
            boolean narrow = w < 110;
            drawClipped(g, setting.label(), x + 5, y + 5, w - (narrow ? 10 : 42), TEXT);
            float state = animate(toggles.getOrDefault(setting, bool.get() ? 1f : 0f), bool.get() ? 1 : 0);
            toggles.put(setting, state);
            int sx = narrow ? x + 6 : x + w - 30, sy = y + (narrow ? 18 : 7);
            rounded(g, sx, sy, 24, 12, 6, blend(0xff435565, drawnAccent, state));
            rounded(g, sx + 2 + (int)(12 * state), sy + 2, 8, 8, 4, 0xfff4ffff);
        } else if (setting instanceof ModeSetting mode) {
            drawClipped(g, setting.label(), x + 5, y + 4, w - 24, TEXT);
            drawClipped(g, mode.get(), x + 5, y + 19, w - 10, drawnAccent);
            draw(g, expandedControls.getOrDefault(setting, false) ? "-" : "+", x + w - 15, y + 4, MUTED);
            int sy = y + SETTING;
            for (String option : mode.options()) {
                option(g, option, mode.get().equals(option), x, sy, w);
                sy += 20;
            }
        } else if (setting instanceof MultiSelectSetting multi) {
            drawClipped(g, multi.label(), x + 5, y + 4, w - 24, TEXT);
            drawClipped(g, multi.get().size() + " selected", x + 5, y + 19, w - 10, drawnAccent);
            draw(g, expandedControls.getOrDefault(setting, false) ? "-" : "+", x + w - 15, y + 4, MUTED);
            int sy = y + SETTING;
            for (String option : multi.options()) { option(g, option, multi.get().contains(option), x, sy, w); sy += 20; }
        } else if (setting instanceof BlockListSetting blocks) {
            drawClipped(g, blocks.label(), x + 5, y + 4, w - 24, TEXT);
            drawClipped(g, blocks.get().size() + " blocks", x + 5, y + 19, w - 10, drawnAccent);
            draw(g, expandedControls.getOrDefault(setting, false) ? "-" : "+", x + w - 15, y + 4, MUTED);
            int sy = y + SETTING;
            rounded(g, x + 2, sy + 1, w - 4, 22, 4, 0x55557183);
            drawClipped(g, editingBlocks == blocks ? blockInput + "_" : "+ registry ID (Enter)", x + 5, sy + 7, w - 10, drawnAccent);
            if (hovered) tooltip = blockStatus;
            sy += 26;
            for (var entry : blocks.get().entrySet()) {
                rounded(g, x + 3, sy + 4, 10, 10, 2, entry.getValue());
                drawClipped(g, entry.getKey(), x + 17, sy + 6, w - 38, entry.getKey().equals(selectedBlock(blocks)) ? drawnAccent : TEXT);
                draw(g, "x", x + w - 14, sy + 6, MUTED);
                sy += 20;
            }
            if (!blocks.get().isEmpty()) renderColor(g, blocks.get().get(selectedBlock(blocks)), "Selected block", blocks, x, sy, w);
        } else if (setting instanceof NumberSetting number) {
            String value = numberLabel(number);
            int valueWidth = font.width(value);
            drawClipped(g, setting.label(), x + 5, y + 5, w - valueWidth - 16, TEXT);
            draw(g, value, x + w - valueWidth - 5, y + 5, MUTED);
            slider(g, x + 6, y + 24, w - 12, (number.get() - number.min()) / (number.max() - number.min()), setting);
        } else if (setting instanceof ColorSetting color) {
            renderColor(g, color.get(), color.label(), setting, x, y, w);
        } else if (setting instanceof GradientSetting gradient) {
            drawClipped(g, gradient.label(), x + 5, y + 5, w - 24, TEXT);
            draw(g, expandedControls.getOrDefault(setting, false) ? "-" : "+", x + w - 15, y + 5, MUTED);
            int sw = Math.max(3, (w - 46) / gradient.get().size());
            int selected = selectedStop(gradient);
            for (int i = 0; i < gradient.get().size(); i++) {
                int sx = x + 5 + i * sw;
                rounded(g, sx, y + 39, sw - 1, 15, 3, gradient.get().get(i).color());
                if (selected == i) RoundedRenderer.outline(g, sx, y + 39, sw - 1, 15, 3, fade(TEXT));
            }
            RoundedRenderer.gradient(g, x + 5, y + 22, w - 10, 9, 3, gradient.get(), eased(open));
            draw(g, "+", x + w - 32, y + 43, TEXT); draw(g, "-", x + w - 16, y + 43, TEXT);
            drawClipped(g, "Stop position", x + 5, y + 65, w - 10, TEXT);
            slider(g, x + 6, y + 84, w - 12, gradient.get().get(selected).position(), gradient);
            renderColor(g, gradient.get().get(selected).color(), "Selected stop", setting, x, y + 94, w);
        }
        g.disableScissor();
    }
    private int selectedStop(GradientSetting setting) {
        return Math.max(0, Math.min(setting.get().size() - 1, gradientSelection.getOrDefault(setting, 0)));
    }
    private String selectedBlock(BlockListSetting blocks) {
        String id = blockSelection.get(blocks);
        if (id == null || !blocks.get().containsKey(id)) id = blocks.get().isEmpty() ? "" : blocks.get().keySet().iterator().next();
        return id;
    }
    private void option(GuiGraphics g, String text, boolean selected, int x, int y, int w) {
        rounded(g, x + 2, y + 1, w - 4, 18, 4, selected ? 0x555f8b99 : 0x22557183);
        drawClipped(g, (selected ? "+ " : "  ") + text, x + 5, y + 6, w - 10, selected ? drawnAccent : MUTED);
    }
    private void renderColor(GuiGraphics g, int color, String label, Setting<?> setting, int x, int y, int w) {
        drawClipped(g, label, x + 5, y + 6, w - 31, TEXT);
        Color.RGBtoHSB((color >> 16) & 255, (color >> 8) & 255, color & 255, hsb);
        rounded(g, x + w - 23, y + 4, 18, 15, 4, 0xff63717d);
        rounded(g, x + w - 23, y + 4, 18, 15, 4, color);
        for (int channel = 0; channel < 4; channel++) {
            draw(g, channel == 0 ? "H" : channel == 1 ? "S" : channel == 2 ? "B" : "A", x + 5, y + 28 + channel * 18, MUTED);
            slider(g, x + 17, y + 32 + channel * 18, w - 23, channel < 3 ? hsb[channel] : (color >>> 24) / 255.0, setting);
        }
        int swatchWidth = Math.max(5, (w - 18) / 4);
        for (int i = 0; i < PRESETS.length; i++)
            rounded(g, x + 5 + i * (swatchWidth + 2), y + 102, swatchWidth, 12, 3, PRESETS[i]);
    }

    private void slider(GuiGraphics g, int x, int y, int w, double fraction, Setting<?> key) {
        if (w < 1) return;
        rounded(g, x, y, w, 3, 1, 0x775f7382);
        float target = (float)Math.max(0, Math.min(1, fraction));
        float value = (key instanceof ColorSetting || key instanceof GradientSetting || key instanceof BlockListSetting) ? target : animate(fills.getOrDefault(key, target), target);
        fills.put(key, value);
        rounded(g, x, y, Math.max(1, (int)(w * value)), 3, 1, drawnAccent);
        rounded(g, x + (int)(w * value) - 3, y - 2, 6, 7, 3, 0xffe9fbff);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;
        editingBlocks = null;
        float mx = transform.local(event.x()), my = transform.local(event.y());
        Panel p = panelAt(mx, my);
        if (p == null) return false;
        panels.remove(p); panels.add(p);
        int x = p.drawX(), y = p.drawY(), w = p.width;
        if (my < y + HEADER) {
            if (event.button() == 1 || (event.button() == 0 && w >= 90 && mx >= x + w - 24)) {
                p.setOpen(!p.state.open());
            } else if (event.button() == 0) {
                draggedPanel = p;
                dragOffsetX = mx - p.x; dragOffsetY = my - p.y;
            }
            return true;
        }
        if (my < y + HEADER + 3 || my >= y + p.drawHeight() - Math.max(5, theme.radius.get().intValue() / 2)) return true;
        if (mx >= x + w - 8 && p.maxScroll() > 0 && event.button() == 0) {
            activePanel = p; scrolling = true; updateScroll(p, my); return true;
        }
        int cursor = Math.round(y + HEADER + 3 - p.scroll);
        for (Module module : modules.in(p.category)) {
            Visual v = visuals.get(module);
            if (inside(mx, my, x + 7, cursor, w - 14, ROW)) {
                if (event.button() == 0) module.toggle();
                else if (event.button() == 1) v.expanded = !v.expanded;
                return true;
            }
            cursor += ROW;
            int expanded = Math.round(moduleSettingsHeight(module) * v.expansion);
            if (my >= cursor && my < cursor + expanded) {
                int sy = cursor + 4;
                for (Setting<?> setting : module.settings()) {
                    if (inside(mx, my, x + 9, sy, w - 18, settingHeight(setting))) {
                        settingClick(p, setting, x + 9, sy, w - 18, mx, my, event.button());
                        return true;
                    }
                    sy += settingHeight(setting);
                }
                if (my >= sy && my < sy + 32 && event.button() == 0) { bindingModule = module; bindingGui = false; }
                return true;
            }
            cursor += expanded;
        }
        if (p.category == Category.CLIENT) {
            if (inside(mx, my, x + 7, cursor, w - 14, ROW)) {
                if (event.button() <= 1) appearance.expanded = !appearance.expanded;
                return true;
            }
            cursor += ROW;
            if (my < cursor || my >= cursor + Math.round(appearanceHeight() * appearance.expansion)) return true;
            int sy = cursor + 4;
            for (Setting<?> setting : theme.settings()) {
                if (inside(mx, my, x + 9, sy, w - 18, settingHeight(setting))) {
                    settingClick(p, setting, x + 9, sy, w - 18, mx, my, event.button());
                    return true;
                }
                sy += settingHeight(setting);
            }
            if (event.button() == 0 && my >= sy && my < sy + 35) { bindingGui = true; bindingModule = null; }
            else if (event.button() == 0 && my >= sy + 35 && my < sy + 70) {
                config.reset();
                for (Panel panel : panels) panel.state = config.panel(panel.category);
            }
        }
        return true;
    }

    private void settingClick(Panel p, Setting<?> setting, int x, int y, int w, float mx, float my, int button) {
        if (button != 0) return;
        if (setting instanceof GroupSetting group) group.toggle();
        else if (setting instanceof ActionSetting action) action.run();
        else if (setting instanceof BooleanSetting bool) bool.toggle();
        else if (setting instanceof ModeSetting mode) {
            int row = (int)(my - y - SETTING) / 20;
            if (my < y + SETTING) expandedControls.put(setting, !expandedControls.getOrDefault(setting, false));
            else if (row >= 0 && row < mode.options().size()) { mode.set(mode.options().get(row)); expandedControls.put(setting, false); }
        } else if (setting instanceof MultiSelectSetting multi) {
            int row = (int)(my - y - SETTING) / 20;
            if (my < y + SETTING) expandedControls.put(setting, !expandedControls.getOrDefault(setting, false));
            else if (row >= 0 && row < multi.options().size()) multi.toggle(multi.options().get(row));
        } else if (setting instanceof BlockListSetting blocks) {
            float local = my - y;
            if (local < SETTING) { expandedControls.put(setting, !expandedControls.getOrDefault(setting, false)); editingBlocks = null; }
            else if (local < SETTING + 26) { editingBlocks = blocks; blockInput = ""; bindingModule = null; bindingGui = false; }
            else {
                int row = (int)(local - SETTING - 26) / 20;
                if (row < blocks.get().size()) {
                    String id = new ArrayList<>(blocks.get().keySet()).get(row);
                    if (mx >= x + w - 22) { blocks.remove(id); activeSetting = null; }
                    else blockSelection.put(blocks, id);
                } else if (!blocks.get().isEmpty()) colorClick(p, blocks, x, y + SETTING + 26 + blocks.get().size() * 20, w, mx, my);
            }
        } else if (setting instanceof NumberSetting number) {
            activeSetting = setting; activePanel = p;
            activeTrack = transform.track(x + 6, w - 12);
            updateNumber(number, mx * scale);
        } else if (setting instanceof ColorSetting) {
            colorClick(p, setting, x, y, w, mx, my);
        } else if (setting instanceof GradientSetting gradient) {
            float localY = my - y;
            if (localY < 34) expandedControls.put(setting, !expandedControls.getOrDefault(setting, false));
            else if (localY < 60) {
                if (mx >= x + w - 36) {
                    if (mx < x + w - 20) gradient.insert(); else gradient.remove(selectedStop(gradient));
                } else {
                    int sw = Math.max(3, (w - 46) / gradient.get().size());
                    gradientSelection.put(gradient, Math.max(0, Math.min(gradient.get().size() - 1, (int)(mx - x - 5) / sw)));
                    expandedControls.put(setting, true);
                }
            } else if (localY < 94) {
                activeSetting = setting; activePanel = p; gradientPosition = true;
                activeTrack = transform.track(x + 6, w - 12);
                updateGradientPosition(gradient, mx * scale);
            } else colorClick(p, setting, x, y + 94, w, mx, my);
        }
    }
    private void colorClick(Panel p, Setting<?> setting, int x, int y, int w, float mx, float my) {
        float localY = my - y;
        if (localY >= 102 && localY < 114) {
            int swatchWidth = Math.max(5, (w - 18) / 4);
            int index = (int)(mx - x - 5) / (swatchWidth + 2);
            if (mx >= x + 5 && index >= 0 && index < PRESETS.length) setColor(setting, PRESETS[index]);
        } else if (localY >= 25 && localY < 98) {
            activeSetting = setting; activePanel = p; gradientPosition = false;
            activeTrack = transform.track(x + 17, w - 23);
            activeColorChannel = Math.max(0, Math.min(3, (int)(localY - 25) / 18));
            updateColor(setting, mx * scale);
        }
    }
    private void setColor(Setting<?> setting, int value) {
        if (setting instanceof ColorSetting color) color.set(value);
        else if (setting instanceof GradientSetting gradient) gradient.color(selectedStop(gradient), value);
        else if (setting instanceof BlockListSetting blocks && !blocks.get().isEmpty()) blocks.put(selectedBlock(blocks), value);
    }
    private void updateGradientPosition(GradientSetting gradient, double screenX) {
        gradientSelection.put(gradient, gradient.position(selectedStop(gradient), activeTrack.fraction(screenX)));
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        float mx = transform.local(event.x()), my = transform.local(event.y());
        if (draggedPanel != null) {
            draggedPanel.drag(mx - dragOffsetX, my - dragOffsetY);
            return true;
        }
        if (activePanel != null) {
            if (scrolling) { updateScroll(activePanel, my); return true; }
            if (activeSetting instanceof NumberSetting number) updateNumber(number, event.x());
            else if (activeSetting instanceof GradientSetting gradient && gradientPosition) updateGradientPosition(gradient, event.x());
            else if (activeSetting instanceof ColorSetting || activeSetting instanceof GradientSetting || activeSetting instanceof BlockListSetting) updateColor(activeSetting, event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    private void updateScroll(Panel p, float mouseY) {
        float top = p.drawY() + HEADER + 3;
        float height = p.drawHeight() - HEADER - 3 - Math.max(5, theme.radius.get().intValue() / 2);
        float thumb = Math.max(12, height * Math.min(1, height / Math.max(1, p.content)));
        p.scrollTarget = clamp((mouseY - top - thumb / 2) / Math.max(1, height - thumb), 0, 1) * p.maxScroll();
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggedPanel != null) draggedPanel.persistPosition();
        draggedPanel = null; activeSetting = null; activeTrack = null; activePanel = null; activeColorChannel = -1; scrolling = false;
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Panel p = panelAt(transform.local(x), transform.local(y));
        if (p == null || !p.state.open()) return false;
        p.scrollTarget = clamp(p.scrollTarget - (float)vertical * 28, 0, p.maxScroll());
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (editingBlocks != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) editingBlocks = null;
            else if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !blockInput.isEmpty()) blockInput = blockInput.substring(0, blockInput.length() - 1);
            else if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                if (editingBlocks.put(blockInput, 0xff58bd8b)) {
                    blockSelection.put(editingBlocks, blockInput.contains(":") ? blockInput : "minecraft:" + blockInput);
                    blockInput = ""; blockStatus = "Added. Select a block below to edit its color";
                } else blockStatus = "Unknown block ID or list full (256 max)";
            } else if (event.key() == GLFW.GLFW_KEY_V && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                String pasted = minecraft.keyboardHandler.getClipboard();
                if (pasted.length() <= 160) blockInput = pasted.trim();
            }
            return true;
        }
        if (bindingModule != null) {
            bindingModule.keybind(event.key() == GLFW.GLFW_KEY_ESCAPE ? -1 : event.key());
            bindingModule = null; return true;
        }
        if (bindingGui) {
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) config.guiKey(event.key());
            bindingGui = false; return true;
        }
        if (event.key() == config.guiKey() && event.key() != GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (editingBlocks == null) return super.charTyped(event);
        int code = event.codepoint();
        if (code < 128 && blockInput.length() < 160 && "abcdefghijklmnopqrstuvwxyz0123456789_:./-".indexOf(code) >= 0) blockInput += (char)code;
        return true;
    }

    private void updateNumber(NumberSetting number, double screenX) {
        number.set(number.min() + activeTrack.fraction(screenX) * (number.max() - number.min()));
    }
    private void updateColor(Setting<?> setting, double screenX) {
        int color = setting instanceof ColorSetting c ? c.get() : setting instanceof BlockListSetting blocks ? blocks.get().getOrDefault(selectedBlock(blocks), 0xff58bd8b) : ((GradientSetting)setting).get().get(selectedStop((GradientSetting)setting)).color();
        Color.RGBtoHSB((color >> 16) & 255, (color >> 8) & 255, color & 255, hsb);
        int alpha = color >>> 24;
        if (activeColorChannel == 3) alpha = (int)(activeTrack.fraction(screenX) * 255);
        else hsb[activeColorChannel] = (float)activeTrack.fraction(screenX);
        setColor(setting, (alpha << 24) | (Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xffffff));
    }
    private Panel panelAt(float x, float y) {
        for (int i = panels.size() - 1; i >= 0; i--) if (panels.get(i).contains(x, y)) return panels.get(i);
        return null;
    }
    private int contentHeight(Panel panel) {
        int height = 0;
        for (Module module : modules.in(panel.category))
            height += ROW + Math.round(moduleSettingsHeight(module) * visuals.get(module).expansion);
        if (panel.category == Category.CLIENT) height += ROW + Math.round(appearanceHeight() * appearance.expansion);
        return Math.max(ROW, height);
    }
    private int appearanceHeight() {
        int height = 78;
        for (Setting<?> setting : theme.settings()) height += settingHeight(setting);
        return height;
    }
    private int moduleSettingsHeight(Module module) {
        int height = 40;
        for (Setting<?> setting : module.settings()) height += settingHeight(setting);
        return height;
    }
    private int settingHeight(Setting<?> setting) {
        int height = SETTING;
        float expansion = controlExpansion.getOrDefault(setting, 0f);
        if (setting instanceof ColorSetting) height = COLOR;
        else if (setting instanceof BlockListSetting blocks) height += Math.round((26 + blocks.get().size() * 20 + (blocks.get().isEmpty() ? 0 : COLOR)) * expansion);
        else if (setting instanceof GradientSetting) height = 60 + Math.round((34 + COLOR) * expansion);
        else if (setting instanceof ModeSetting mode) height += Math.round(mode.options().size() * 20 * expansion);
        else if (setting instanceof MultiSelectSetting multi) height += Math.round(multi.options().size() * 20 * expansion);
        if (setting.group() != null) height = Math.round(height * groupExpansion.getOrDefault(setting.group(), setting.group().get() ? 1f : 0f));
        return height;
    }
    private String numberLabel(NumberSetting number) {
        Double current = number.get();
        if (!current.equals(labelValues.get(number))) {
            labelValues.put(number, current);
            labels.put(number, number.step() >= 1 ? Integer.toString(current.intValue())
                : Double.toString(Math.round(current * 100) / 100.0));
        }
        return labels.get(number);
    }
    private float response(float rate) {
        return theme.animations.get() ? 1 - (float)Math.exp(-rate * theme.animationSpeed.get() * dt) : 1;
    }
    private float animate(float current, float target) {
        float rate = theme.easing.get().equals("Snappy") ? 22 : theme.easing.get().equals("Bouncy") ? 10 : 14;
        float next = current + (target - current) * response(rate);
        return Math.abs(target - next) < 0.001f ? target : next;
    }
    private static float eased(float x) { return x * x * (3 - 2 * x); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static boolean inside(float x, float y, int left, int top, int width, int height) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }
    private static int blend(int a, int b, float f) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int value = (int)(((a >>> shift) & 255) * (1 - f) + ((b >>> shift) & 255) * f);
            result |= value << shift;
        }
        return result;
    }
    private int fade(int color) { return ((int)((color >>> 24) * eased(open)) << 24) | (color & 0xffffff); }
    private void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        RoundedRenderer.rounded(g, x, y, w, h, radius, fade(color));
    }
    private void draw(GuiGraphics g, String value, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(transform.snap(x) - x, transform.snap(y) - y);
        g.drawString(font, value, x, y, fade(color), false);
        g.pose().popMatrix();
    }
    private void drawClipped(GuiGraphics g, String value, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        g.enableScissor(x, y, x + maxWidth, y + 10);
        draw(g, value, x, y, color);
        g.disableScissor();
    }
    private void renderTooltip(GuiGraphics g, float mx, float my, int screenWidth, int screenHeight) {
        if (tooltip != null && tooltip.equals(lastTooltip)) tooltipTime += dt;
        else { lastTooltip = tooltip; tooltipTime = 0; }
        if (tooltip == null || tooltipTime < 0.55f) return;
        int w = Math.min(Math.min(320, screenWidth - 10), font.width(tooltip) + 16);
        var lines = font.split(Component.literal(tooltip), Math.max(1, w - 16));
        int h = lines.size() * 11 + 12;
        int x = (int)clamp(mx + 9, 5, Math.max(5, screenWidth - w - 5));
        int y = (int)clamp(my + 13, 5, Math.max(5, screenHeight - h - 5));
        g.nextStratum();
        rounded(g, x, y, w, h, 6, 0xf0223343);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 8, y + 7 + i * 11, fade(TEXT), false);
    }
    private static String keyName(int key) {
        if (key < 0) return "None";
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) return "Right Shift";
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) return "Left Shift";
        if (key == GLFW.GLFW_KEY_ESCAPE) return "Escape";
        String name = GLFW.glfwGetKeyName(key, 0);
        return name == null ? "Key " + key : name.toUpperCase();
    }

    private final class Panel {
        final Category category;
        PanelState state;
        float x, y, targetX, targetY, scroll, scrollTarget, height = HEADER, headerHover;
        int width, content, viewportWidth, viewportHeight;
        boolean initialized;
        Panel(Category category) { this.category = category; state = config.panel(category); }
        void layout(PanelLayout layout, float autoY, int content, int screenWidth, int screenHeight) {
            int width = layout.width();
            this.width = width; this.content = content;
            viewportWidth = screenWidth; viewportHeight = screenHeight;
            float wantedHeight = state.open() ? Math.min(HEADER + content + 9, (state.automatic() ? layout.cellHeight() : screenHeight - 8)) : HEADER;
            height = animate(height, Math.max(HEADER, wantedHeight));
            // A shrinking viewport must constrain the current frame, not just the animation target.
            if (state.automatic()) height = Math.min(height, layout.cellHeight());
            if (draggedPanel != this) {
                targetX = state.automatic() ? layout.x(category.ordinal()) : 4 + (float)state.x() * Math.max(0, screenWidth - width - 8);
                targetY = state.automatic() ? autoY : 4 + (float)state.y() * Math.max(0, screenHeight - HEADER - 8);
            }
            targetX = clamp(targetX, 4, Math.max(4, screenWidth - width - 4));
            targetY = clamp(targetY, 4, Math.max(4, screenHeight - drawHeight() - 4));
            if (!initialized) { x = targetX; y = targetY; initialized = true; }
            x = clamp(state.automatic() && draggedPanel != this ? targetX : animate(x, targetX), 4, Math.max(4, screenWidth - width - 4));
            y = clamp(state.automatic() && draggedPanel != this ? targetY : animate(y, targetY), 4, Math.max(4, screenHeight - drawHeight() - 4));
            scrollTarget = clamp(scrollTarget, 0, maxScroll());
            scroll = animate(scroll, scrollTarget);
        }
        void setOpen(boolean open) {
            state = new PanelState(state.x(), state.y(), open, state.automatic());
            config.panel(category, state);
        }
        void drag(float x, float y) { targetX = x; targetY = y; }
        void persistPosition() {
            state = new PanelState((targetX - 4) / Math.max(1, viewportWidth - width - 8),
                (targetY - 4) / Math.max(1, viewportHeight - HEADER - 8), state.open());
            config.panel(category, state);
        }
        float maxScroll() { return Math.max(0, content - (drawHeight() - HEADER - 9)); }
        int drawX() { return Math.round(x); }
        int drawY() { return Math.round(y); }
        int drawHeight() { return Math.round(height); }
        boolean contains(float px, float py) {
            int x = drawX(), y = drawY(), h = drawHeight();
            if (!inside(px, py, x, y, width, h)) return false;
            float r = Math.min(theme.radius.get().floatValue(), Math.min(width, h) / 2f);
            float qx = Math.max(Math.abs(px - x - width / 2f) - width / 2f + r, 0);
            float qy = Math.max(Math.abs(py - y - h / 2f) - h / 2f + r, 0);
            return qx * qx + qy * qy <= r * r;
        }
    }
    private static final class Visual { boolean expanded; float expansion, toggle, hover; }
}
