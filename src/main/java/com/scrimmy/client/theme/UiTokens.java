package com.scrimmy.client.theme;

/** Shared visual rhythm and motion constants. Values use logical GUI pixels / seconds. */
public final class UiTokens {
    private UiTokens() {}
    public static final int HEADER = 30, ROW = 36, SETTING = 34, COLOR = 120;
    public static final int TEXT = 0xffe5f1f8, MUTED = 0xff9cb3c2, BORDER = 0x668da9bc;
    public static final int SOLID = 0xff121b27;
    public static final double TRANSITION = .22, TOOLTIP_DELAY = .5;
    public static final float STATE_FREQUENCY = 22, DRAG_FREQUENCY = 32, SCROLL_FREQUENCY = 18;
    public static final float MIN_ALPHA = .001f;
}
