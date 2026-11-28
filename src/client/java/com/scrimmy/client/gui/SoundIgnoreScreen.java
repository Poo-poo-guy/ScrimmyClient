package com.scrimmy.client.gui;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.render.PanelBlur;
import com.scrimmy.client.render.RoundedRenderer;
import com.scrimmy.client.render.ClientFonts;
import com.scrimmy.client.render.ThemeSurfaces;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Uses the existing action-setting convention without changing ClickGUI's controls or layout. */
public final class SoundIgnoreScreen extends Screen {
    private final SoundFinder module;
    private final Screen parent;
    private final PanelBlur blur = new PanelBlur();
    private EditBox input;
    public SoundIgnoreScreen(SoundFinder module, Screen parent) {
        super(Component.literal("SoundFinder ignore list")); this.module = module; this.parent = parent;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        String value = input == null ? module.ignoreList() : input.getValue();
        int w = Math.min(620, width - 32), x = (width - w) / 2, y = height / 2;
        input = new EditBox(ClientFonts.widgetFont(), x + 12, y - 10, w - 24, 20, Component.literal("Comma-separated sound prefixes"));
        input.setMaxLength(4096); input.setValue(value); addRenderableWidget(input); setInitialFocus(input);
        addRenderableWidget(Button.builder(ClientFonts.component("Save"), b -> { module.ignoreList(input.getValue()); onClose(); })
                .bounds(x + 12, y + 30, 80, 20).build());
        addRenderableWidget(Button.builder(ClientFonts.component("Cancel"), b -> onClose()).bounds(x + 100, y + 30, 80, 20).build());
    }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (ScrimmyClient.THEME.blur.get() >= 1) blur.capture(minecraft); else blur.close();
        g.fill(0, 0, width, height, 0x44081018);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        int w = Math.min(620, width - 32), x = (width - w) / 2, y = height / 2;
        int tint = ThemeSurfaces.fill();
        if (blur.texture() != null) RoundedRenderer.glass(g, x, y - 64, w, 130, 10, 0xffffffff, blur.texture());
        RoundedRenderer.panel(g, x, y - 64, w, 130, 10, tint, ScrimmyClient.THEME.accent.get(), .2f, .5f);
        ClientFonts.draw(g, title.getString(), x + 12, y - 50, ScrimmyClient.THEME.accent.get());
        ClientFonts.draw(g, "Comma-separated prefixes; empty list captures all sounds.", x + 12, y - 32, 0xffc4d3e3);
        super.render(g, mx, my, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { blur.close(); }
}
