package com.scrimmy.client.notification;

/** Invoked for a new visible toast or an accepted state change inside an existing toast. */
@FunctionalInterface
public interface NotificationSound {
    NotificationSound SILENT = enabled -> {};
    void onShown(boolean enabled);
}
