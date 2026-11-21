package com.scrimmy.client.notification;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Registered exactly once by the client initializer; resources are decoded by Minecraft's sound cache. */
public final class ClientNotificationSounds {
    public static final Identifier AUDIO = Identifier.fromNamespaceAndPath("scrimmyclient", "sounds/aula_click1.ogg");
    public static final SoundEvent ON = sound("notification_on");
    public static final SoundEvent OFF = sound("notification_off");
    private static boolean registered;
    private ClientNotificationSounds() {}
    private static SoundEvent sound(String name) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("scrimmyclient", name));
    }
    public static void register() {
        if (registered) return;
        Registry.register(BuiltInRegistries.SOUND_EVENT, ON.location(), ON);
        Registry.register(BuiltInRegistries.SOUND_EVENT, OFF.location(), OFF);
        registered = true;
    }
}
