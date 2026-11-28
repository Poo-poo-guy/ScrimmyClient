package com.scrimmy.client.notification;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.Module;

import net.minecraft.client.Minecraft;

/** Input sources annotate toggles; the sole enabled-state hook delivers them here after the real change. */
public final class NotificationCenter {
    public static final ToastQueue TOASTS = new ToastQueue();
    private NotificationCenter() {}
    public static NotificationSettings settings() { return ScrimmyClient.THEME.notifications; }
    public static void sound(NotificationSound sound) { TOASTS.sound(sound); }
    public static void moduleToggled(Module module, boolean enabled, Module.ToggleSource source) {
        NotificationSettings settings = settings();
        if (!settings.enabled.get()) { TOASTS.clear(); return; }
        if (settings.source.get().equals("Keybind only") && source != Module.ToggleSource.KEYBIND) return;
        TOASTS.show(module, module.name(), enabled, System.nanoTime() * 1e-9, settings.duration.get(), settings.selectedStyle());
    }
    public static void tick() {
        if (!settings().enabled.get()) { TOASTS.clear(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.font != null && mc.getOverlay() == null) NotificationRenderer.prepare();
    }
}
