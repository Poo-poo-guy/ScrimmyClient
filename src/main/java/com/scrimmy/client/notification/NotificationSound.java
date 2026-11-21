package com.scrimmy.client.notification;

/** Extension point only: no audio is played until an implementation is supplied. */
@FunctionalInterface
public interface NotificationSound {
    NotificationSound SILENT = enabled -> {};
    void onShown(boolean enabled);
}
