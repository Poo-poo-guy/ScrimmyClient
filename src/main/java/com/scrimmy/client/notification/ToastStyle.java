package com.scrimmy.client.notification;

/** A toast retains its style throughout its visible lifetime, including duplicate refreshes. */
public enum ToastStyle {
    CARD(40), PILL(26);
    private final int height;
    ToastStyle(int height) { this.height = height; }
    public int height() { return height; }
}
