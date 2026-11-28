package com.scrimmy.client.gui;

import com.scrimmy.client.config.ConfigStore;
import com.scrimmy.client.animation.*;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.render.ThemeSurfaces;
import com.scrimmy.client.theme.UiTokens;
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
    private static final int HEADER = UiTokens.HEADER, ROW = UiTokens.ROW, SETTING = UiTokens.SETTING, COLOR = UiTokens.COLOR;
    private static final String APPEARANCE = "ScrimmyClient+";
    private static final int TEXT = UiTokens.TEXT, MUTED = UiTokens.MUTED;
    private static final int[] PRESETS = {0xff70d6db, 0xffb693ee, 0xfff494a5, 0xffffcf78};
    private final Theme theme;
    private final ModuleManager modules;
    private final ConfigStore config;
    private final List<Panel> panels = new ArrayList<>();
    private final float[] rowHeights = new float[Category.values().length];
    private final PanelBlur blur = new PanelBlur();
    private final Visual appearance = new Visual();
    private final Map<Module, Visual> visuals = new IdentityHashMap<>();
    private final Map<Setting<?>, ControlVisual> controls = new IdentityHashMap<>();
    private final Map<GradientSetting, Integer> gradientSelection = new IdentityHashMap<>();
    private final Map<BlockListSetting, String> blockSelection = new IdentityHashMap<>();
    private BlockListSetting editingBlocks;
    private String blockInput = "", blockStatus = "Enter ID to add; click x to remove";
    private boolean gradientPosition, scrolling;
    private final float[] hsb = new float[3];
    private final Animation opening = new Animation(), tooltipFade = new Animation();
    private final Animation scaleMotion = new Animation(), zoomMotion = new Animation(1);
    private PanelLayout cachedLayout;
    private int layoutWidth = -1, layoutHeight = -1, layoutPanelWidth = -1;
    private float drawOpacity = 1, originX, originY;
    private final Animation guiBinding = new Animation();
    private AnimationStyle style;
    private float motionFrequency, motionDamping, motionSlide, motionStagger;
    private String[] tooltipLines = new String[0];
    private int tooltipWidth, tooltipFontRevision = -1;
    private float open, dt, scale;
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
    private final AnimatedColor accentMotion;
    private String tooltip;
    private float tooltipTime;
    private String lastTooltip, hoveredTooltip;

    public ClickGui(Theme theme, ModuleManager modules, ConfigStore config) {
        super(Component.literal("Scrimmy Client"));
        this.theme = theme; this.modules = modules; this.config = config;
        scale = theme.guiScale.get().floatValue();
        scaleMotion.snap(scale);
        style = AnimationStyle.get(theme.animation.get());
        motionFrequency = (float)style.frequency(); motionDamping = (float)style.damping();
        motionSlide = style.slide(); motionStagger = style.stagger();
        transform = new GuiTransform(scale, minecraft.getWindow().getGuiScale());
        drawnAccent = theme.accent.get();
        accentMotion = new AnimatedColor(drawnAccent);
        for (Category category : Category.values()) panels.add(new Panel(category));
        modules.all().forEach(m -> {
            visuals.put(m, new Visual());
            for (Setting<?> setting : m.settings()) controls.put(setting, new ControlVisual(setting));
        });
        for (Setting<?> setting : theme.settings()) controls.put(setting, new ControlVisual(setting));
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
        int wanted = Math.round(theme.blur.get().floatValue() * ThemeSurfaces.glass() * clamp(open, 0, 1));
        if (minecraft.options.getMenuBackgroundBlurriness() != wanted)
            minecraft.options.menuBackgroundBlurriness().set(wanted);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        updateBlur();
        if (minecraft.options.getMenuBackgroundBlurriness() >= 1 && ThemeSurfaces.glass() > .001f) blur.capture(minecraft);
        g.fill(0, 0, width, height, fade(0x22081018));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        dt = (float)AnimationClock.UI.delta();
        style = AnimationStyle.get(theme.animation.get());
        float mix = response(18);
        motionFrequency += ((float)style.frequency() - motionFrequency) * mix;
        motionDamping += ((float)style.damping() - motionDamping) * mix;
        motionSlide += (style.slide() - motionSlide) * mix;
        motionStagger += (style.stagger() - motionStagger) * mix;
        double seconds = dt * theme.animationSpeed.get();
        open = theme.animations.get() ? opening.tween(closing ? 0 : 1, seconds,
            style.duration(), style.easing(), closing ? motionStagger * panels.size() : 0) : closing ? 0 : 1;
        scale = motion(scaleMotion, theme.guiScale.get());
        float zoom = motion(zoomMotion, 1 - style.zoom() * (1 - clamp(open, 0, 1)));
        float renderedScale = scale * zoom;
        originX = width * (1 - zoom) / 2; originY = height * (1 - zoom) / 2;
        if (transform.scale() != renderedScale || transform.minecraftScale() != minecraft.getWindow().getGuiScale())
            transform.update(renderedScale, minecraft.getWindow().getGuiScale());
        drawnAccent = accentMotion.update(theme.accent.get(), seconds, UiTokens.STATE_FREQUENCY, 1);
        if (closing && open < .001f && panelsClosed()) { minecraft.setScreen(null); return; }
        float mx = localX(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()));
        float my = localY(minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
        int logicalWidth = Math.max(1, (int)(width / scale));
        int logicalHeight = Math.max(1, (int)(height / scale));
        int gap = logicalWidth < 400 ? 4 : 8;
        int preferredWidth = theme.panelWidth.get().intValue();
        if (cachedLayout == null || layoutWidth != logicalWidth || layoutHeight != logicalHeight || layoutPanelWidth != preferredWidth) {
            cachedLayout = PanelLayout.fit(logicalWidth, logicalHeight, preferredWidth, panels.size(), gap);
            layoutWidth = logicalWidth; layoutHeight = logicalHeight; layoutPanelWidth = preferredWidth;
        }
        PanelLayout layout = cachedLayout;
        for (Module module : modules.all()) {
            for (Setting<?> setting : module.settings()) animateSetting(setting);
            Visual v = visuals.get(module);
            v.expansion = motion(v.expandMotion, v.expanded ? 1 : 0);
            v.toggle = motion(v.toggleMotion, module.enabled() ? 1 : 0);
            int index = modules.in(module.category()).indexOf(module);
            double order = closing ? panels.size() - 1 - module.category().ordinal() + Math.max(0, modules.in(module.category()).size() - 1 - index) * .35
                : module.category().ordinal() + index * .35;
            v.reveal = reveal(v.revealMotion, order);
        }
        for (Setting<?> setting : theme.settings()) animateSetting(setting);
        appearance.expansion = motion(appearance.expandMotion, appearance.expanded ? 1 : 0);
        appearance.reveal = reveal(appearance.revealMotion, Category.CLIENT.ordinal() + (closing ? 0 : modules.in(Category.CLIENT).size() * .35));
        motion(guiBinding, bindingGui ? 1 : 0);
        java.util.Arrays.fill(rowHeights, HEADER);
        for (Panel panel : panels) if (panel.state.automatic()) {
            float wanted = panel.state.open() ? Math.min(HEADER + contentHeight(panel) + 9, layout.cellHeight()) : HEADER;
            int row = panel.category.ordinal() / layout.columns();
            rowHeights[row] = Math.max(rowHeights[row], Math.min(layout.cellHeight(), wanted));
        }
        for (Panel panel : panels) {
            float autoY = 4;
            for (int row = 0; row < panel.category.ordinal() / layout.columns(); row++) autoY += rowHeights[row] + gap;
            panel.reveal = reveal(panel.revealMotion, closing ? panels.size() - 1 - panel.category.ordinal() : panel.category.ordinal());
            panel.layout(layout, autoY, contentHeight(panel), logicalWidth, logicalHeight);
        }
        Panel hovered = panelAt(mx, my);
        tooltip = null;
        g.pose().pushMatrix();
        g.pose().translate(originX, originY);
        g.pose().scale(renderedScale, renderedScale);
        for (Panel panel : panels) {
            // Each panel owns a stratum, so text and controls cannot interleave with another panel.
            g.nextStratum();
            drawOpacity = clamp(panel.reveal, 0, 1);
            g.pose().pushMatrix();
            g.pose().translate(panel.x - panel.drawX(), panel.y + motionSlide * (1 - panel.reveal) - panel.drawY());
            renderPanel(g, panel, hovered == panel ? mx : -10000, hovered == panel ? my : -10000);
            g.pose().popMatrix();
        }
        drawOpacity = 1;
        renderTooltip(g, mx, my, logicalWidth, logicalHeight);
        g.pose().popMatrix();
    }

    private void renderPanel(GuiGraphics g, Panel p, float mx, float my) {
        int x = p.drawX(), y = p.drawY(), w = p.width, h = p.drawHeight();
        int radius = theme.radius.get().intValue();
        float glass = ThemeSurfaces.glass();
        int fill = ThemeSurfaces.fill();
        if (blur.texture() != null && glass > .001f)
            RoundedRenderer.glass(g, x, y, w, h, radius, fade(((int)(255 * glass) << 24) | 0xffffff), blur.texture());
        RoundedRenderer.panel(g, x, y, w, h, radius, fade(fill), fade(UiTokens.BORDER),
            theme.glow.get().floatValue() * open * drawOpacity, theme.shadow.get().floatValue() * open * drawOpacity);
        rounded(g, x + Math.max(4, radius), y + 1, w - Math.max(4, radius) * 2, 1, 0, 0x30ffffff);
        p.headerHover = motion(p.hoverMotion, inside(mx, my, x, y, w, HEADER) ? 1 : 0);
        rounded(g, x + 3, y + 3, w - 6, HEADER - 6, Math.min(radius, 8), ((int)(12 + 14 * p.headerHover) << 24) | 0xffffff);
        int titlePadding = w < 90 ? 4 : 9;
        if (p.category == Category.BASEFINDING && ClientFonts.width(p.category.title()) > w - 8) {
            draw(g, p.category.title().substring(0, 4), x + 5, y + 5, TEXT);
            draw(g, p.category.title().substring(4), x + 5, y + 17, TEXT);
        } else {
            g.enableScissor(x + titlePadding, y + 8, x + w - (w < 90 ? 3 : 22), y + 25);
            ClientFonts.drawBold(g, p.category.title(), x + titlePadding, y + 10, fade(TEXT));
            g.disableScissor();
        }
        if (w >= 90) draw(g, p.state.open() ? "-" : "+", x + w - 15, y + 11, MUTED);
        int top = y + HEADER + 3, bottom = y + h - Math.max(5, radius / 2);
        if (bottom <= top) return;
        rounded(g, x + 8, y + HEADER - 1, w - 16, 1, 0, 0x33ffffff);
        // The scissor stays inside the rounded corners; no content crosses their mask.
        g.enableScissor(x + 5, top, x + w - 5, bottom);
        g.pose().pushMatrix();
        g.pose().translate(0, top - p.scroll - Math.round(top - p.scroll));
        int cursor = Math.round(top - p.scroll);
        if (modules.in(p.category).isEmpty() && p.category != Category.CLIENT) {
            drawClipped(g, "No modules yet", x + 10, cursor + 10, w - 20, MUTED);
        }
        for (Module module : modules.in(p.category)) {
            Visual v = visuals.get(module);
            float panelOpacity = drawOpacity;
            drawOpacity *= clamp(v.reveal, 0, 1);
            renderModuleRow(g, module, v, x, cursor + rowSlide(v), w, mx, my);
            cursor += ROW;
            int expanded = Math.round(moduleSettingsHeight(module) * v.expansion);
            if (expanded > 0 && cursor < bottom && cursor + expanded > top) {
                g.enableScissor(x + 7, Math.max(top, cursor), x + w - 7, Math.min(cursor + expanded, bottom));
                drawOpacity *= clamp(v.expansion, 0, 1);
                int sy = cursor + 4;
                for (Setting<?> setting : module.settings()) {
                    renderSetting(g, setting, x + 9, sy, w - 18, mx, my);
                    sy += settingHeight(setting);
                }
                renderKeybind(g, module, x + 9, sy, w - 18);
                g.disableScissor();
            }
            cursor += expanded;
            drawOpacity = panelOpacity;
        }
        if (p.category == Category.CLIENT) {
            float panelOpacity = drawOpacity;
            drawOpacity *= clamp(appearance.reveal, 0, 1);
            appearance.hover = motion(appearance.hoverMotion, inside(mx, my, x + 7, cursor, w - 14, ROW) ? 1 : 0);
            rounded(g, x + 7, cursor + 2, w - 14, ROW - 4, 6, ((int)(25 + appearance.hover * 22) << 24) | 0xffffff);
            if (ClientFonts.width(APPEARANCE) <= w - 14) {
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
                drawOpacity *= clamp(appearance.expansion, 0, 1);
                renderAppearance(g, x + 9, cursor + 4, w - 18, mx, my);
                g.disableScissor();
            }
            drawOpacity = panelOpacity;
        }
        g.pose().popMatrix();
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
        v.hover = motion(v.hoverMotion, inside(mx, my, x + 7, y, w - 14, ROW) && g.containsPointInScissor((int)(mx * transform.scale() + originX), (int)(my * transform.scale() + originY)) ? 1 : 0);
        rounded(g, x + 7, y + 2, w - 14, ROW - 4, 6, blend(((int)(24 + 24 * v.hover) << 24) | 0xffffff, 0x28000000 | (drawnAccent & 0xffffff), v.toggle * .5f));
        boolean narrow = w < 125;
        drawClipped(g, module.name(), x + (narrow ? 8 : 12), y + (narrow ? 5 : 12), w - (narrow ? 16 : 65), TEXT);
        int sx = narrow ? x + 12 : x + w - 43, sy = y + (narrow ? 19 : 11);
        rounded(g, sx, sy, 24, 12, 6, blend(0xff435565, drawnAccent, v.toggle));
        roundedSmooth(g, sx + 2 + 12 * v.toggle, sy + 2, 8, 8, 4, 0xfff5ffff);
        if (narrow) draw(g, v.expanded ? "-" : "+", x + w - 18, y + 20, MUTED);
        if (inside(mx, my, x + 7, y, w - 14, ROW) && g.containsPointInScissor((int)(mx * transform.scale() + originX), (int)(my * transform.scale() + originY))) tooltip = module.description();
    }

    private void renderAppearance(GuiGraphics g, int x, int y, int w, float mx, float my) {
        for (Setting<?> setting : theme.settings()) {
            renderSetting(g, setting, x, y, w, mx, my);
            y += settingHeight(setting);
        }
        rounded(g, x, y + 2, w, 29, 6, blend(0x55557183, drawnAccent, guiBinding.value() * .3f));
        drawClipped(g, bindingGui ? "Press a key..." : "GUI key: " + keyName(config.guiKey()), x + 7, y + 12, w - 14, TEXT);
        y += 35;
        rounded(g, x, y + 2, w, 29, 6, 0x557c5760);
        drawClipped(g, "Reset to defaults", x + 7, y + 12, w - 14, TEXT);
    }

    private void renderKeybind(GuiGraphics g, Module module, int x, int y, int w) {
        Visual visual = visuals.get(module);
        float state = motion(visual.bindMotion, bindingModule == module ? 1 : 0);
        rounded(g, x, y + 2, w, 28, 6, blend(0x55557183, drawnAccent, state * .3f));
        drawClipped(g, bindingModule == module ? "Press a key (Esc clears)" : "Key: " + keyName(module.keybind()), x + 6, y + 11, w - 12, TEXT);
    }

    private void animateSetting(Setting<?> setting) {
        ControlVisual v = controls.get(setting);
        if (setting instanceof GroupSetting group) motion(v.group, group.get() ? 1 : 0);
        motion(v.expansion, v.expanded ? 1 : 0);
        if (setting instanceof BooleanSetting bool) motion(v.toggle, bool.get() ? 1 : 0);
        if (setting instanceof NumberSetting number) motion(v.label, number.get());
        if (setting instanceof ModeSetting mode)
            for (int i = 0; i < v.options.length; i++) motion(v.options[i], mode.options().get(i).equals(mode.get()) ? 1 : 0);
        else if (setting instanceof MultiSelectSetting multi)
            for (int i = 0; i < v.options.length; i++) motion(v.options[i], multi.get().contains(multi.options().get(i)) ? 1 : 0);
    }

    private void renderSetting(GuiGraphics g, Setting<?> setting, int x, int y, int w, float mx, float my) {
        int h = settingHeight(setting);
        if (h <= 0) return;
        g.enableScissor(x, y, x + w, y + h);
        var clip = g.scissorStack.peek();
        if (clip != null && (clip.width() <= 0 || clip.height() <= 0)) { g.disableScissor(); return; }
        boolean hovered = inside(mx, my, x, y, w, h) && g.containsPointInScissor((int)(mx * transform.scale() + originX), (int)(my * transform.scale() + originY));
        ControlVisual visual = controls.get(setting);
        float hover = motion(visual.hover, hovered ? 1 : 0);
        if (hovered) tooltip = setting.tooltip();
        if (hover > 0.01f) rounded(g, x, y + 1, w, h - 2, 5, ((int)(22 * hover) << 24) | 0xffffff);
        if (setting instanceof com.scrimmy.client.module.basefinding.ChunkFinderV2.RankedSetting ranks) {
            var rows = ranks.module.visible();
            drawClipped(g, "Best chunks", x + 5, y + 6, w - 10, drawnAccent);
            if (rows.isEmpty()) drawClipped(g, "No scores above minimum", x + 5, y + 25, w - 10, MUTED);
            for (int i = 0; i < Math.min(5, rows.size()); i++) {
                var row = rows.get(i); int ry = y + 22 + i * 34;
                rounded(g, x + 2, ry, w - 4, 31, 5, 0x335f7c91);
                drawClipped(g, row.x() + ", " + row.z() + "  " + Math.round(row.score()) + "/100", x + 6, ry + 3, w - 12, TEXT);
                drawClipped(g, "Send to DrillTrail", x + 6, ry + 17, w - 12, drawnAccent);
            }
        } else if (setting instanceof GroupSetting group) {
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
            float state = visual.toggle.value();
            int sx = narrow ? x + 6 : x + w - 30, sy = y + (narrow ? 18 : 7);
            rounded(g, sx, sy, 24, 12, 6, blend(0xff435565, drawnAccent, state));
            roundedSmooth(g, sx + 2 + 12 * state, sy + 2, 8, 8, 4, 0xfff4ffff);
        } else if (setting instanceof ModeSetting mode) {
            drawClipped(g, setting.label(), x + 5, y + 4, w - 24, TEXT);
            float opacity = drawOpacity;
            for (int i = 0; i < visual.options.length; i++) {
                drawOpacity = opacity * clamp(visual.options[i].value(), 0, 1);
                if (drawOpacity > .001f) drawClipped(g, mode.options().get(i), x + 5, y + 19, w - 10, drawnAccent);
            }
            drawOpacity = opacity;
            draw(g, controls.get(setting).expanded ? "-" : "+", x + w - 15, y + 4, MUTED);
            int sy = y + SETTING;
            if (visual.expansion.value() > .001f) for (String option : mode.options()) {
                option(g, option, mode.get().equals(option), controls.get(setting).options[mode.options().indexOf(option)], x, sy, w);
                sy += 20;
            }
        } else if (setting instanceof MultiSelectSetting multi) {
            drawClipped(g, multi.label(), x + 5, y + 4, w - 24, TEXT);
            drawClipped(g, multi.get().size() + " selected", x + 5, y + 19, w - 10, drawnAccent);
            draw(g, controls.get(setting).expanded ? "-" : "+", x + w - 15, y + 4, MUTED);
            int sy = y + SETTING;
            if (visual.expansion.value() > .001f) for (String option : multi.options()) { option(g, option, multi.get().contains(option), controls.get(setting).options[multi.options().indexOf(option)], x, sy, w); sy += 20; }
        } else if (setting instanceof BlockListSetting blocks) {
            drawClipped(g, blocks.label(), x + 5, y + 4, w - 24, TEXT);
            drawClipped(g, blocks.get().size() + " blocks", x + 5, y + 19, w - 10, drawnAccent);
            draw(g, controls.get(setting).expanded ? "-" : "+", x + w - 15, y + 4, MUTED);
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
            int valueWidth = ClientFonts.width(value);
            drawClipped(g, setting.label(), x + 5, y + 5, w - valueWidth - 16, TEXT);
            draw(g, value, x + w - valueWidth - 5, y + 5, MUTED);
            slider(g, x + 6, y + 24, w - 12, (number.get() - number.min()) / (number.max() - number.min()), setting);
        } else if (setting instanceof ColorSetting color) {
            renderColor(g, color.get(), color.label(), setting, x, y, w);
        } else if (setting instanceof GradientSetting gradient) {
            drawClipped(g, gradient.label(), x + 5, y + 5, w - 24, TEXT);
            draw(g, controls.get(setting).expanded ? "-" : "+", x + w - 15, y + 5, MUTED);
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
            slider(g, x + 6, y + 84, w - 12, gradient.get().get(selected).position(), gradient, 4);
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
    private void option(GuiGraphics g, String text, boolean selected, Animation animation, int x, int y, int w) {
        float state = animation.value();
        rounded(g, x + 2, y + 1, w - 4, 18, 4, blend(0x22557183, 0x555f8b99, state));
        drawClipped(g, (selected ? "+ " : "  ") + text, x + 5, y + 6, w - 10, blend(MUTED, drawnAccent, state));
    }
    private void renderColor(GuiGraphics g, int color, String label, Setting<?> setting, int x, int y, int w) {
        ControlVisual visual = controls.get(setting);

        color = visual.colorMotion.update(color, dt * theme.animationSpeed.get(), UiTokens.STATE_FREQUENCY, 1);
        drawClipped(g, label, x + 5, y + 6, w - 31, TEXT);
        Color.RGBtoHSB((color >> 16) & 255, (color >> 8) & 255, color & 255, hsb);
        rounded(g, x + w - 23, y + 4, 18, 15, 4, 0xff63717d);
        rounded(g, x + w - 23, y + 4, 18, 15, 4, color);
        for (int channel = 0; channel < 4; channel++) {
            draw(g, channel == 0 ? "H" : channel == 1 ? "S" : channel == 2 ? "B" : "A", x + 5, y + 28 + channel * 18, MUTED);
            slider(g, x + 17, y + 32 + channel * 18, w - 23, channel < 3 ? hsb[channel] : (color >>> 24) / 255.0, setting, channel);
        }
        int swatchWidth = Math.max(5, (w - 18) / 4);
        for (int i = 0; i < PRESETS.length; i++)
            rounded(g, x + 5 + i * (swatchWidth + 2), y + 102, swatchWidth, 12, 3, PRESETS[i]);
    }

    private void slider(GuiGraphics g, int x, int y, int w, double fraction, Setting<?> key) {
        slider(g, x, y, w, fraction, key, 0);
    }
    private void slider(GuiGraphics g, int x, int y, int w, double fraction, Setting<?> key, int channel) {
        if (w < 1) return;
        rounded(g, x, y, w, 3, 1, 0x775f7382);
        float target = (float)Math.max(0, Math.min(1, fraction));
        float value = motion(controls.get(key).fills[channel], target);
        roundedSmooth(g, x, y, Math.max(1, w * value), 3, 1, drawnAccent);
        roundedSmooth(g, x + w * value - 3, y - 2, 6, 7, 3, 0xffe9fbff);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;
        editingBlocks = null;
        float mx = localX(event.x()), my = localY(event.y());
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
                if (event.button() == 0) module.toggle(Module.ToggleSource.CLICK_GUI);
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
        if (setting instanceof com.scrimmy.client.module.basefinding.ChunkFinderV2.RankedSetting ranks) {
            int row = (int)(my - y - 22) / 34;
            var rows = ranks.module.visible();
            if (my >= y + 22 && row >= 0 && row < Math.min(5, rows.size())) ranks.module.sendToDrillTrail(rows.get(row));
        }
        else if (setting instanceof GroupSetting group) group.toggle();
        else if (setting instanceof ActionSetting action) action.run();
        else if (setting instanceof BooleanSetting bool) bool.toggle();
        else if (setting instanceof ModeSetting mode) {
            int row = (int)(my - y - SETTING) / 20;
            if (my < y + SETTING) controls.get(setting).expanded = !controls.get(setting).expanded;
            else if (row >= 0 && row < mode.options().size()) { mode.set(mode.options().get(row)); controls.get(setting).expanded = false; }
        } else if (setting instanceof MultiSelectSetting multi) {
            int row = (int)(my - y - SETTING) / 20;
            if (my < y + SETTING) controls.get(setting).expanded = !controls.get(setting).expanded;
            else if (row >= 0 && row < multi.options().size()) multi.toggle(multi.options().get(row));
        } else if (setting instanceof BlockListSetting blocks) {
            float local = my - y;
            if (local < SETTING) { controls.get(setting).expanded = !controls.get(setting).expanded; editingBlocks = null; }
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
            activeTrack = track(x + 6, w - 12);
            updateNumber(number, mx * transform.scale() + originX);
        } else if (setting instanceof ColorSetting) {
            colorClick(p, setting, x, y, w, mx, my);
        } else if (setting instanceof GradientSetting gradient) {
            float localY = my - y;
            if (localY < 34) controls.get(setting).expanded = !controls.get(setting).expanded;
            else if (localY < 60) {
                if (mx >= x + w - 36) {
                    if (mx < x + w - 20) gradient.insert(); else gradient.remove(selectedStop(gradient));
                } else {
                    int sw = Math.max(3, (w - 46) / gradient.get().size());
                    gradientSelection.put(gradient, Math.max(0, Math.min(gradient.get().size() - 1, (int)(mx - x - 5) / sw)));
                    controls.get(setting).expanded = true;
                }
            } else if (localY < 94) {
                activeSetting = setting; activePanel = p; gradientPosition = true;
                activeTrack = track(x + 6, w - 12);
                updateGradientPosition(gradient, mx * transform.scale() + originX);
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
            activeTrack = track(x + 17, w - 23);
            activeColorChannel = Math.max(0, Math.min(3, (int)(localY - 25) / 18));
            updateColor(setting, mx * transform.scale() + originX);
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
        float mx = localX(event.x()), my = localY(event.y());
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
        Panel p = panelAt(localX(x), localY(y));
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
        if (event.key() == config.guiKey() && event.key() != GLFW.GLFW_KEY_ESCAPE) {
            if (closing) closing = false; else onClose();
            return true;
        }
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
        if (setting instanceof com.scrimmy.client.module.basefinding.ChunkFinderV2.RankedSetting ranks)
            height = 22 + Math.max(1, Math.min(5, ranks.module.visible().size())) * 34;
        float expansion = clamp(controls.get(setting).expansion.value(), 0, 1);
        if (setting instanceof ColorSetting) height = COLOR;
        else if (setting instanceof BlockListSetting blocks) height += Math.round((26 + blocks.get().size() * 20 + (blocks.get().isEmpty() ? 0 : COLOR)) * expansion);
        else if (setting instanceof GradientSetting) height = 60 + Math.round((34 + COLOR) * expansion);
        else if (setting instanceof ModeSetting mode) height += Math.round(mode.options().size() * 20 * expansion);
        else if (setting instanceof MultiSelectSetting multi) height += Math.round(multi.options().size() * 20 * expansion);
        if (setting.group() != null) height = Math.round(height * clamp(controls.get(setting.group()).group.value(), 0, 1));
        return height;
    }
    private String numberLabel(NumberSetting number) {
        if (number == theme.notifications.cornerRadius && number.get() < 0) return "Auto";
        ControlVisual visual = controls.get(number);
        double animated = visual.label.value();
        double current = number.step() >= 1 ? Math.round(animated) : Math.round(animated * 100) / 100.0;
        if (visual.labelText == null || current != visual.displayedLabel) {
            visual.displayedLabel = current;
            visual.labelText = number.step() >= 1 ? Long.toString(Math.round(current)) : Double.toString(current);
        }
        return visual.labelText;
    }
    private float response(float rate) {
        return theme.animations.get() ? 1 - (float)Math.exp(-rate * theme.animationSpeed.get() * dt) : 1;
    }
    private float motion(Animation animation, double target) {
        if (!theme.animations.get()) { animation.snap(target); return animation.value(); }
        return animation.spring(target, dt * theme.animationSpeed.get(), motionFrequency, motionDamping);
    }
    private float reveal(Animation animation, double index) {
        if (!theme.animations.get()) { animation.snap(closing ? 0 : 1); return animation.value(); }
        return animation.tween(closing ? 0 : 1, dt * theme.animationSpeed.get(), style.duration(), style.easing(), index * motionStagger);
    }
    private boolean panelsClosed() {
        for (Panel panel : panels) if (panel.reveal > .001f) return false;
        return true;
    }
    private int rowSlide(Visual visual) { return Math.round(motionSlide * (1 - visual.reveal) * (motionStagger > .001f ? 1 : 0)); }
    private float localX(double x) { return transform.local(x - originX); }
    private float localY(double y) { return transform.local(y - originY); }
    private GuiTransform.Track track(float x, float width) { return new GuiTransform.Track(x * transform.scale() + originX, width * transform.scale()); }
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
    private int fade(int color) { return ((int)((color >>> 24) * clamp(open, 0, 1) * drawOpacity) << 24) | (color & 0xffffff); }
    private void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        RoundedRenderer.rounded(g, x, y, w, h, radius, fade(color));
    }
    private void roundedSmooth(GuiGraphics g, float x, float y, float w, int h, int radius, int color) {
        int left = (int)Math.floor(x), top = (int)Math.floor(y), width = Math.max(1, (int)Math.ceil(w));
        g.pose().pushMatrix();
        g.pose().translate(x - left, y - top);
        g.pose().translate(left, top);
        g.pose().scale(w / width, 1);
        rounded(g, 0, 0, width, h, radius, color);
        g.pose().popMatrix();
    }
    private void draw(GuiGraphics g, String value, int x, int y, int color) {
        if ((fade(color) >>> 24) == 0) return;
        ClientFonts.draw(g, value, x, y, fade(color));
    }
    private void drawClipped(GuiGraphics g, String value, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        g.enableScissor(x, y - 3, x + maxWidth, y + 12);
        draw(g, value, x, y, color);
        g.disableScissor();
    }
    private void renderTooltip(GuiGraphics g, float mx, float my, int screenWidth, int screenHeight) {
        boolean sameHover = tooltip != null && tooltip.equals(hoveredTooltip);
        if (sameHover) tooltipTime += dt * theme.animationSpeed.get();
        else { hoveredTooltip = tooltip; tooltipTime = 0; }
        boolean same = tooltip != null && tooltip.equals(lastTooltip);
        float opacity = motion(tooltipFade, same && tooltipTime >= UiTokens.TOOLTIP_DELAY ? 1 : 0);
        if (!same && opacity < .01f && tooltip != null) {
            lastTooltip = tooltip;
            tooltipLines = ClientFonts.wrap(tooltip, Math.max(1, Math.min(304, screenWidth - 26)));
            tooltipFontRevision = ClientFonts.revision();
        }
        if (lastTooltip != null && tooltipFontRevision != ClientFonts.revision()) {
            tooltipLines = ClientFonts.wrap(lastTooltip, Math.max(1, Math.min(304, screenWidth - 26)));
            tooltipFontRevision = ClientFonts.revision();
        }
        if (opacity < .001f || tooltipLines.length == 0) return;
        tooltipWidth = 0;
        for (String line : tooltipLines) tooltipWidth = Math.max(tooltipWidth, ClientFonts.width(line));
        int w = Math.min(screenWidth - 10, tooltipWidth + 16), h = tooltipLines.length * 12 + 12;
        int x = (int)clamp(mx + 9, 5, Math.max(5, screenWidth - w - 5));
        int y = (int)clamp(my + 13, 5, Math.max(5, screenHeight - h - 5));
        drawOpacity = opacity;
        g.nextStratum();
        rounded(g, x, y, w, h, 6, 0xf0223343);
        for (int i = 0; i < tooltipLines.length; i++) draw(g, tooltipLines[i], x + 8, y + 7 + i * 12, TEXT);
        drawOpacity = 1;
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
        float reveal;
        final Animation revealMotion = new Animation(), hoverMotion = new Animation();
        final Animation heightMotion = new Animation(HEADER), xMotion = new Animation(), yMotion = new Animation(), scrollMotion = new Animation();
        Panel(Category category) { this.category = category; state = config.panel(category); }
        void layout(PanelLayout layout, float autoY, int content, int screenWidth, int screenHeight) {
            int width = layout.width();
            this.width = width; this.content = content;
            viewportWidth = screenWidth; viewportHeight = screenHeight;
            float wantedHeight = state.open() ? Math.min(HEADER + content + 9, (state.automatic() ? layout.cellHeight() : screenHeight - 8)) : HEADER;
            height = motion(heightMotion, Math.max(HEADER, wantedHeight));
            // A shrinking viewport must constrain the current frame, not just the animation target.
            if (state.automatic()) height = Math.min(height, layout.cellHeight());
            if (draggedPanel != this) {
                targetX = state.automatic() ? layout.x(category.ordinal()) : 4 + (float)state.x() * Math.max(0, screenWidth - width - 8);
                targetY = state.automatic() ? autoY : 4 + (float)state.y() * Math.max(0, screenHeight - HEADER - 8);
            }
            targetX = clamp(targetX, 4, Math.max(4, screenWidth - width - 4));
            targetY = clamp(targetY, 4, Math.max(4, screenHeight - drawHeight() - 4));
            if (!initialized) { xMotion.snap(targetX); yMotion.snap(targetY); x = targetX; y = targetY; initialized = true; }
            x = clamp(draggedPanel == this ? xMotion.spring(targetX, dt * theme.animationSpeed.get(), UiTokens.DRAG_FREQUENCY, 1) : motion(xMotion, targetX), 4, Math.max(4, screenWidth - width - 4));
            y = clamp(draggedPanel == this ? yMotion.spring(targetY, dt * theme.animationSpeed.get(), UiTokens.DRAG_FREQUENCY, 1) : motion(yMotion, targetY), 4, Math.max(4, screenHeight - drawHeight() - 4));
            scrollTarget = clamp(scrollTarget, 0, maxScroll());
            scroll = theme.animations.get() ? scrollMotion.spring(scrollTarget, dt * theme.animationSpeed.get(), UiTokens.SCROLL_FREQUENCY, 1) : scrollTarget;
            if (!theme.animations.get()) scrollMotion.snap(scrollTarget);
            scroll = clamp(scroll, 0, maxScroll());
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
        int drawY() { return Math.round(y + motionSlide * (1 - reveal)); }
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
    private static final class Visual {
        boolean expanded;
        float expansion, toggle, hover, reveal;
        final Animation expandMotion = new Animation(), toggleMotion = new Animation(), hoverMotion = new Animation();
        final Animation revealMotion = new Animation(), bindMotion = new Animation();
    }
    private static final class ControlVisual {
        boolean expanded;
        final AnimatedColor colorMotion;
        double displayedLabel;
        String labelText;
        final Animation expansion = new Animation(), hover = new Animation(), toggle, group, label;
        final Animation[] fills = {new Animation(), new Animation(), new Animation(), new Animation(), new Animation()};
        final Animation[] options;
        ControlVisual(Setting<?> setting) {
            int initialColor = setting instanceof ColorSetting c ? c.get()
                : setting instanceof GradientSetting gradient ? gradient.get().getFirst().color()
                : setting instanceof BlockListSetting blocks && !blocks.get().isEmpty() ? blocks.get().values().iterator().next() : 0xffffffff;
            colorMotion = new AnimatedColor(initialColor);
            toggle = new Animation(setting instanceof BooleanSetting b && b.get() ? 1 : 0);
            group = new Animation(setting instanceof GroupSetting g && g.get() ? 1 : 0);
            label = new Animation(setting instanceof NumberSetting n ? n.get() : 0);
            int count = setting instanceof ModeSetting m ? m.options().size() : setting instanceof MultiSelectSetting m ? m.options().size() : 0;
            options = new Animation[count];
            for (int i = 0; i < count; i++) {
                boolean selected = setting instanceof ModeSetting mode ? mode.options().get(i).equals(mode.get())
                    : setting instanceof MultiSelectSetting multi && multi.get().contains(multi.options().get(i));
                options[i] = new Animation(selected ? 1 : 0);
            }
        }
    }
}
