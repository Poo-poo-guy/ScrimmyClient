package com.scrimmy.client;

import com.scrimmy.client.config.ConfigStore;
import com.scrimmy.client.gui.ClickGui;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.module.ModuleManager;
import com.scrimmy.client.theme.Theme;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class ScrimmyClient implements ClientModInitializer {
    public static final Theme THEME = new Theme();
    public static final ModuleManager MODULES = new ModuleManager();
    public static final ConfigStore CONFIG = new ConfigStore(THEME, MODULES);
    private final Map<Integer, Boolean> lastKeys = new HashMap<>();
    private final Set<Integer> sampledKeys = new HashSet<>(), pressedKeys = new HashSet<>();

    @Override public void onInitializeClient() {
        CONFIG.load();
        MODULES.all().forEach(Module::initialize);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (client.getWindow() == null) return;
        sampledKeys.clear(); pressedKeys.clear();
        int guiKey = CONFIG.guiKey();
        if (pressed(client, guiKey) && client.screen == null) client.setScreen(new ClickGui(THEME, MODULES, CONFIG));
        for (Module module : MODULES.all()) {
            int key = module.keybind();
            if (key >= 0 && key != guiKey && pressed(client, key) && client.screen == null) module.toggle();
        }
        MODULES.tick(client);
        CONFIG.tick();
    }

    private boolean pressed(Minecraft client, int key) {
        if (key < 0 || key > GLFW.GLFW_KEY_LAST) return false;
        if (!sampledKeys.add(key)) return pressedKeys.contains(key);
        boolean down = GLFW.glfwGetKey(client.getWindow().handle(), key) == GLFW.GLFW_PRESS;
        boolean wasDown = lastKeys.getOrDefault(key, false);
        lastKeys.put(key, down);
        if (down && !wasDown) pressedKeys.add(key);
        return down && !wasDown;
    }
}
