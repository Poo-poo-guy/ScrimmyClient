package com.scrimmy.client.gui;

import com.scrimmy.client.config.ConfigStore;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.module.ModuleManager;
import com.scrimmy.client.render.RoundedRenderer;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.theme.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ClickGui extends Screen {
    private static final int HEADER = 32, ROW = 34, SETTING = 34, COLOR = 100;
    private static final int TEXT = 0xffe5f1f8, MUTED = 0xff9cb3c2;
    private static final int[] PRESETS = {0xff70d6db, 0xffb693ee, 0xfff494a5, 0xffffcf78};
    private final Theme theme;
    private final ModuleManager modules;
    private final ConfigStore config;
    private final Panel combat = new Panel("Combat", 24, 54);
    private final Panel appearance = new Panel("Appearance", 248, 54);
    private final Map<Module, Visual> visuals = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> fills = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> toggles = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> hovers = new IdentityHashMap<>();
    private final Map<NumberSetting, Double> labelValues = new IdentityHashMap<>();
    private final Map<NumberSetting, String> labels = new IdentityHashMap<>();
    private long lastFrame = System.nanoTime();
    private float open, dt = 1f / 60f;
    private boolean closing, dragging;
    private Panel draggedPanel;
    private float dragOffsetX, dragOffsetY;
    private Setting<?> activeSetting;
    private int activeColorChannel = -1;
    private Module bindingModule;
    private boolean bindingGui;
    private float scale = 1;
    private int previousGameBlur = -1;
    private final float[] hsb = new float[3];
    private int drawnAccent;

    public ClickGui(Theme theme, ModuleManager modules, ConfigStore config) {
        super(Component.literal("Scrimmy Client"));
        this.theme = theme; this.modules = modules; this.config = config;
        drawnAccent = theme.accent.get();
        modules.all().forEach(m -> visuals.put(m, new Visual()));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { closing = true; }
    @Override public void added() {
        previousGameBlur = minecraft.options.getMenuBackgroundBlurriness();
        updateBlur();
    }
    @Override public void removed() {
        if (previousGameBlur >= 0) minecraft.options.menuBackgroundBlurriness().set(previousGameBlur);
        config.save();
    }
    private void updateBlur() {
        int wanted = theme.blur.get().intValue();
        if (minecraft.options.getMenuBackgroundBlurriness() != wanted)
            minecraft.options.menuBackgroundBlurriness().set(wanted);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        updateBlur();
        if (theme.blur.get() >= 1) g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, fade(0x44081018));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        open = animate(open, closing ? 0 : 1);
        drawnAccent = blend(drawnAccent, theme.accent.get(),
            theme.animations.get() ? 1 - (float)Math.exp(-14 * theme.animationSpeed.get() * dt) : 1);
        if (closing && open < 0.02f) { minecraft.setScreen(null); return; }
        scale = theme.guiScale.get().floatValue() * (0.96f + 0.04f * eased(open));
        int logicalWidth = Math.max(1, (int)(width / scale));
        int logicalHeight = Math.max(1, (int)(height / scale));
        int mx = (int)(mouseX / scale), my = (int)(mouseY / scale);
        int panelWidth = Math.min(theme.panelWidth.get().intValue(), Math.max(100, logicalWidth - 12));
        if (!dragging && appearance.x < combat.x + panelWidth + 8 && logicalWidth >= panelWidth * 2 + 30)
            appearance.targetX = combat.x + panelWidth + 12;
        combat.layout(panelWidth, combatContent(), logicalWidth, logicalHeight);
        appearance.layout(panelWidth, appearanceContent(), logicalWidth, logicalHeight);

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        renderPanel(g, combat, mx, my);
        renderPanel(g, appearance, mx, my);
        g.pose().popMatrix();
    }

    private void renderPanel(GuiGraphics g, Panel p, int mx, int my) {
        int x = Math.round(p.x), y = Math.round(p.y), w = p.width, h = p.height;
        int fill = (Math.min(255, (int)(theme.opacity.get() * 255)) << 24) | (theme.glassTint.get() & 0xffffff);
        int border = 0x668da9bc;
        RoundedRenderer.panel(g, x, y, w, h, theme.radius.get().intValue(), fade(fill), fade(border),
            theme.glow.get().floatValue() * open, theme.shadow.get().floatValue() * open);
        draw(g, p.title, x + 14, y + 12, TEXT);
        g.fill(x + 10, y + HEADER - 1, x + w - 10, y + HEADER, fade(0x44ffffff));

        int top = y + HEADER + 2, bottom = y + h - 5;
        g.enableScissor(x + 4, top, x + w - 4, bottom);
        int cursor = Math.round(top - p.scroll);
        if (p == combat) renderModules(g, x, cursor, w, mx, my, bottom);
        else renderAppearance(g, x, cursor, w, mx, my);
        g.disableScissor();
    }

    private void renderModules(GuiGraphics g, int x, int cursor, int w, int mx, int my, int bottom) {
        for (Module module : modules.all()) {
            Visual v = visuals.get(module);
            v.expansion = animate(v.expansion, v.expanded ? 1 : 0);
            v.toggle = animate(v.toggle, module.enabled() ? 1 : 0);
            v.hover = animate(v.hover, inside(mx, my, x + 7, cursor, w - 14, ROW) ? 1 : 0);
            int accent = drawnAccent;
            rounded(g, x + 7, cursor + 3, w - 14, ROW - 5, Math.max(3, theme.radius.get().intValue() - 4),
                (int)(42 + 26 * v.hover) << 24 | 0xffffff);
            draw(g, module.name(), x + 17, cursor + 12, TEXT);
            draw(g, v.expanded ? "-" : "+", x + w - 18, cursor + 12, MUTED);
            int switchX = x + w - 59;
            rounded(g, switchX, cursor + 10, 27, 14, 7, blend(0x8851636f, accent, v.toggle));
            rounded(g, switchX + 2 + (int)(13 * v.toggle), cursor + 12, 10, 10, 5, 0xfff5ffff);
            cursor += ROW;
            int expanded = Math.round(moduleSettingsHeight(module) * v.expansion);
            if (expanded > 0 && cursor < bottom) {
                g.enableScissor(x + 7, cursor, x + w - 7, Math.min(cursor + expanded, bottom));
                int sy = cursor + 4;
                for (Setting<?> setting : module.settings()) {
                    renderSetting(g, setting, x + 11, sy, w - 22, mx, my);
                    sy += settingHeight(setting);
                }
                renderKeybind(g, module, x + 11, sy, w - 22);
                g.disableScissor();
            }
            cursor += expanded;
        }
    }

    private void renderAppearance(GuiGraphics g, int x, int cursor, int w, int mx, int my) {
        int sy = cursor + 4;
        for (Setting<?> setting : theme.settings()) {
            renderSetting(g, setting, x + 11, sy, w - 22, mx, my);
            sy += settingHeight(setting);
        }
        rounded(g, x + 11, sy + 2, w - 22, 29, 7, 0x55557183);
        draw(g, bindingGui ? "Press a key..." : "GUI key: " + keyName(config.guiKey()), x + 19, sy + 12, TEXT);
        sy += 35;
        rounded(g, x + 11, sy + 2, w - 22, 29, 7, 0x557c5760);
        draw(g, "Reset to defaults", x + 19, sy + 12, TEXT);
    }

    private void renderKeybind(GuiGraphics g, Module module, int x, int y, int w) {
        rounded(g, x, y + 2, w, 28, 7, 0x55557183);
        draw(g, bindingModule == module ? "Press a key (Esc clears)" : "Keybind: " + keyName(module.keybind()), x + 8, y + 11, TEXT);
    }

    private void renderSetting(GuiGraphics g, Setting<?> setting, int x, int y, int w, int mx, int my) {
        int h = settingHeight(setting);
        float hover = animate(hovers.getOrDefault(setting, 0f), inside(mx, my, x, y, w, h) ? 1 : 0);
        hovers.put(setting, hover);
        if (hover > 0.01f)
            rounded(g, x, y + 1, w, h - 2, 6, ((int)(34 * hover) << 24) | 0xffffff);
        draw(g, setting.label(), x + 8, y + 7, TEXT);
        if (setting instanceof BooleanSetting bool) {
            float state = animate(toggles.getOrDefault(setting, bool.get() ? 1f : 0f), bool.get() ? 1 : 0);
            toggles.put(setting, state);
            rounded(g, x + w - 33, y + 7, 25, 14, 7, blend(0xff526573, drawnAccent, state));
            rounded(g, x + w - 31 + (int)(11 * state), y + 9, 10, 10, 5, 0xfff4ffff);
        } else if (setting instanceof ModeSetting mode) {
            String value = mode.get();
            draw(g, value, x + w - font.width(value) - 8, y + 7, drawnAccent);
        } else if (setting instanceof NumberSetting number) {
            String value = numberLabel(number);
            draw(g, value, x + w - font.width(value) - 8, y + 7, MUTED);
            slider(g, x + 8, y + 24, w - 16, (number.get() - number.min()) / (number.max() - number.min()), setting);
        } else if (setting instanceof ColorSetting color) {
            Color.RGBtoHSB((color.get() >> 16) & 255, (color.get() >> 8) & 255, color.get() & 255, hsb);
            rounded(g, x + w - 33, y + 5, 25, 16, 5, color.get());
            for (int channel = 0; channel < 3; channel++) {
                String label = channel == 0 ? "H" : channel == 1 ? "S" : "B";
                draw(g, label, x + 9, y + 29 + channel * 18, MUTED);
                slider(g, x + 25, y + 32 + channel * 18, w - 34, hsb[channel], setting);
            }
            for (int i = 0; i < PRESETS.length; i++)
                rounded(g, x + 8 + i * 23, y + 82, 17, 13, 4, PRESETS[i]);
        }
    }

    private void slider(GuiGraphics g, int x, int y, int w, double fraction, Setting<?> key) {
        rounded(g, x, y, w, 4, 2, 0x775f7382);
        float target = (float)Math.max(0, Math.min(1, fraction));
        float value = key instanceof ColorSetting ? target : animate(fills.getOrDefault(key, target), target);
        fills.put(key, value);
        rounded(g, x, y, Math.max(1, (int)(w * value)), 4, 2, drawnAccent);
        rounded(g, x + (int)(w * value) - 3, y - 2, 7, 8, 3, 0xffe9fbff);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int)(event.x() / scale), my = (int)(event.y() / scale);
        Panel p = panelAt(mx, my);
        if (p == null) return false;
        int x = Math.round(p.x), y = Math.round(p.y), w = p.width;
        if (my < y + HEADER && event.button() == 0) {
            draggedPanel = p; dragging = true; dragOffsetX = mx - p.targetX; dragOffsetY = my - p.targetY;
            return true;
        }
        if (my < y + HEADER + 2 || my > y + p.height - 5) return true;
        int cursor = Math.round(y + HEADER + 2 - p.scroll);
        if (p == combat) {
            for (Module module : modules.all()) {
                Visual v = visuals.get(module);
                if (inside(mx, my, x + 7, cursor, w - 14, ROW)) {
                    if (event.button() == 0) module.toggle();
                    else if (event.button() == 1) v.expanded = !v.expanded;
                    return true;
                }
                cursor += ROW;
                int expanded = Math.round(moduleSettingsHeight(module) * v.expansion);
                if (expanded > 0 && my >= cursor && my < cursor + expanded) {
                    int sy = cursor + 4;
                    for (Setting<?> setting : module.settings()) {
                        if (my >= sy && my < sy + settingHeight(setting)) {
                            settingClick(setting, x + 11, sy, w - 22, mx, my, event.button());
                            return true;
                        }
                        sy += settingHeight(setting);
                    }
                    if (my >= sy && my < sy + 32) { bindingModule = module; bindingGui = false; return true; }
                }
                cursor += expanded;
            }
        } else {
            int sy = cursor + 4;
            for (Setting<?> setting : theme.settings()) {
                if (my >= sy && my < sy + settingHeight(setting)) {
                    settingClick(setting, x + 11, sy, w - 22, mx, my, event.button());
                    return true;
                }
                sy += settingHeight(setting);
            }
            if (my >= sy && my < sy + 35) { bindingGui = true; bindingModule = null; return true; }
            if (my >= sy + 35 && my < sy + 70) { config.reset(); return true; }
        }
        return true;
    }

    private void settingClick(Setting<?> setting, int x, int y, int w, int mx, int my, int button) {
        if (setting instanceof BooleanSetting bool) bool.toggle();
        else if (setting instanceof ModeSetting mode) mode.next();
        else if (setting instanceof NumberSetting number) {
            activeSetting = setting;
            updateNumber(number, x, w, mx);
        } else if (setting instanceof ColorSetting color) {
            int localY = my - y;
            if (localY >= 80) {
                int i = (mx - x - 8) / 23;
                if (i >= 0 && i < PRESETS.length) color.set(PRESETS[i]);
            } else if (localY >= 25 && localY < 80) {
                activeSetting = setting;
                activeColorChannel = Math.max(0, Math.min(2, (localY - 25) / 18));
                updateColor(color, x, w, mx);
            }
        }
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        int mx = (int)(event.x() / scale), my = (int)(event.y() / scale);
        if (dragging && draggedPanel != null) {
            draggedPanel.targetX = mx - dragOffsetX;
            draggedPanel.targetY = my - dragOffsetY;
            return true;
        }
        if (activeSetting instanceof NumberSetting number) {
            Panel p = activeSettingOwner();
            if (p != null) updateNumber(number, Math.round(p.x) + 11, p.width - 22, mx);
            return true;
        }
        if (activeSetting instanceof ColorSetting color) {
            Panel p = activeSettingOwner();
            if (p != null) updateColor(color, Math.round(p.x) + 11, p.width - 22, mx);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false; draggedPanel = null; activeSetting = null; activeColorChannel = -1;
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Panel p = panelAt((int)(x / scale), (int)(y / scale));
        if (p == null) return false;
        p.scrollTarget = clamp(p.scrollTarget - (float)vertical * 28, 0, p.maxScroll());
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
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

    private Panel activeSettingOwner() { return theme.settings().contains(activeSetting) ? appearance : combat; }
    private void updateNumber(NumberSetting number, int x, int w, int mx) {
        double portion = clamp((mx - x - 8f) / Math.max(1, w - 16), 0, 1);
        number.set(number.min() + portion * (number.max() - number.min()));
    }
    private void updateColor(ColorSetting color, int x, int w, int mx) {
        Color.RGBtoHSB((color.get() >> 16) & 255, (color.get() >> 8) & 255, color.get() & 255, hsb);
        hsb[activeColorChannel] = clamp((mx - x - 25f) / Math.max(1, w - 34), 0, 1);
        color.set(0xff000000 | (Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xffffff));
    }

    private Panel panelAt(int x, int y) {
        if (appearance.contains(x, y)) return appearance;
        if (combat.contains(x, y)) return combat;
        return null;
    }
    private int combatContent() {
        int n = 4;
        for (Module module : modules.all()) {
            Visual v = visuals.get(module);
            n += ROW + Math.round(moduleSettingsHeight(module) * v.expansion);
        }
        return n;
    }
    private int appearanceContent() {
        int n = 8 + 70;
        for (Setting<?> setting : theme.settings()) n += settingHeight(setting);
        return n;
    }
    private int moduleSettingsHeight(Module module) {
        int n = 40;
        for (Setting<?> setting : module.settings()) n += settingHeight(setting);
        return n;
    }
    private static int settingHeight(Setting<?> setting) { return setting instanceof ColorSetting ? COLOR : SETTING; }
    private String numberLabel(NumberSetting number) {
        Double current = number.get();
        if (!current.equals(labelValues.get(number))) {
            labelValues.put(number, current);
            labels.put(number, number.step() >= 1 ? Integer.toString(current.intValue())
                : Double.toString(Math.round(current * 100) / 100.0));
        }
        return labels.get(number);
    }
    private float animate(float current, float target) {
        if (!theme.animations.get()) return target;
        float speed = theme.animationSpeed.get().floatValue();
        float base = theme.easing.get().equals("Snappy") ? 22 : theme.easing.get().equals("Bouncy") ? 10 : 14;
        float next = current + (target - current) * (1 - (float)Math.exp(-base * speed * dt));
        if (theme.easing.get().equals("Bouncy") && Math.abs(target - next) > 0.02f)
            next += (target - next) * 0.05f * (float)Math.sin(System.nanoTime() / 80_000_000.0);
        return Math.abs(target - next) < 0.001f ? target : next;
    }
    private static float eased(float x) { return x * x * (3 - 2 * x); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static boolean inside(int x, int y, int left, int top, int width, int height) {
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
    private int fade(int color) {
        return ((int)((color >>> 24) * eased(open)) << 24) | (color & 0xffffff);
    }
    private void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        RoundedRenderer.rounded(g, x, y, w, h, radius, fade(color));
    }
    private void draw(GuiGraphics g, String value, int x, int y, int color) {
        g.drawString(font, value, x, y, fade(color));
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
        final String title;
        float x, y, targetX, targetY, scroll, scrollTarget;
        int width, height, content;
        Panel(String title, float x, float y) {
            this.title = title; this.x = x; this.y = y; this.targetX = x; this.targetY = y;
        }
        void layout(int width, int content, int screenWidth, int screenHeight) {
            this.width = width; this.content = content;
            this.height = Math.min(HEADER + content + 6, Math.max(50, screenHeight - 12));
            targetX = clamp(targetX, 4, Math.max(4, screenWidth - width - 4));
            targetY = clamp(targetY, 4, Math.max(4, screenHeight - height - 4));
            x = animate(x, targetX); y = animate(y, targetY);
            scrollTarget = clamp(scrollTarget, 0, maxScroll());
            scroll = animate(scroll, scrollTarget);
        }
        float maxScroll() { return Math.max(0, content - (height - HEADER - 7)); }
        boolean contains(int px, int py) { return inside(px, py, Math.round(x), Math.round(y), width, height); }
    }
    private static final class Visual {
        boolean expanded;
        float expansion, toggle, hover;
    }
}
