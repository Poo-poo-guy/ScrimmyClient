package com.scrimmy.client.notification;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Unregistered client-only handles. SoundManager resolves the unchanged JSON assets by identifier. */
public final class ClientNotificationSounds {
    public static final Identifier AUDIO = Identifier.fromNamespaceAndPath("scrimmyclient", "sounds/aula_click1.ogg");
    public static final SoundEvent ON = sound("notification_on");
    public static final SoundEvent OFF = sound("notification_off");
    private ClientNotificationSounds() {}
    private static SoundEvent sound(String name) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("scrimmyclient", name));
    }
}
