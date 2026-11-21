package com.scrimmy.client.notification;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** The existing toast hook owns requests; this provider only handles UI playback and same-tick coalescing. */
public final class NotificationAudio implements NotificationSound {
    private static final NotificationAudio INSTANCE = new NotificationAudio();
    private static boolean initialized;
    private final NotificationSoundLimiter limiter = new NotificationSoundLimiter();
    private final RandomSource random = RandomSource.create();
    private volatile boolean available;
    private boolean pending, pendingEnabled;
    private NotificationAudio() {}
    public static void initialize() {
        if (initialized) return;
        NotificationCenter.sound(INSTANCE);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            INSTANCE.limiter.beginTick();
            flush(); // Requests admitted by rendering since the last client tick.
        });
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(
                Identifier.fromNamespaceAndPath("scrimmyclient", "notification_sounds"),
                (ResourceManagerReloadListener) manager -> INSTANCE.available = manager.getResource(ClientNotificationSounds.AUDIO).isPresent());
        initialized = true;
    }
    @Override public void onShown(boolean enabled) {
        NotificationSettings settings = NotificationCenter.settings();
        if (!available || !settings.enabled.get() || !settings.sound.get() || settings.volume.get() <= 0) return;
        // The hook can run during queued-toast admission in rendering: retain only primitive state.
        // Actual sound-instance allocation and playback happen on client ticks.
        pending = true;
        pendingEnabled = enabled;
    }
    public static void flush() { INSTANCE.playPending(); }
    private void playPending() {
        if (!pending) return;
        boolean enabled = pendingEnabled;
        pending = false; // Muted, unavailable and throttled requests are dropped, never played late.
        NotificationSettings settings = NotificationCenter.settings();
        if (!available || !settings.enabled.get() || !settings.sound.get() || settings.volume.get() <= 0) return;
        Minecraft client = Minecraft.getInstance();
        if (client.options.getSoundSourceVolume(SoundSource.MASTER) <= 0 || client.options.getSoundSourceVolume(SoundSource.UI) <= 0) return;
        var event = enabled ? ClientNotificationSounds.ON : ClientNotificationSounds.OFF;
        var definition = client.getSoundManager().getSoundEvent(event.location());
        if (definition == null || definition.getWeight() == 0 || !limiter.claim(true)) return;
        float sample = settings.pitchVariation.get() ? random.nextFloat() : .5f;
        float pitch = NotificationSoundLimiter.pitch(enabled, settings.pitchVariation.get(), sample);
        // forUI uses SoundSource.UI, listener-relative coordinates and Attenuation.NONE in 1.21.11.
        // Pass only our volume; Minecraft applies master/UI gain itself, exactly once.
        client.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, settings.volume.get().floatValue()));
    }
}
