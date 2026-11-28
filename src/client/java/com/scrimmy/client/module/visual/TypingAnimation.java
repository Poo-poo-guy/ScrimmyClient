package com.scrimmy.client.module.visual;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.setting.BooleanSetting;
import com.scrimmy.client.setting.ModeSetting;
import com.scrimmy.client.setting.NumberSetting;
import com.scrimmy.client.typing.TextAnimator;

public final class TypingAnimation extends Module {
    public final ModeSetting style = add(new ModeSetting("style", "Style", "Fade", "Fade", "Slide", "Pop"));
    public final NumberSetting speed = add(new NumberSetting("speed", "Speed (ms)", 140, 50, 250, 10));
    public final BooleanSetting smoothCaret = add(new BooleanSetting("smoothCaret", "Smooth Caret", true));
    public final BooleanSetting chat = add(new BooleanSetting("chat", "Chat", true));
    public final BooleanSetting signsAndBooks = add(new BooleanSetting("signsAndBooks", "Signs and Books", true));
    public final BooleanSetting otherFields = add(new BooleanSetting("otherFields", "Other Fields", true));

    public TypingAnimation() {
        super("TypingAnimation", "Animate typed input without changing its value", Category.VISUAL);
        style.tooltip("Fade, slide upward, or pop newly entered text into view.");
        speed.tooltip("Animation duration in milliseconds; rapid typing automatically settles sooner.");
        smoothCaret.tooltip("Ease the input caret and selection, with a soft caret blink.");
        chat.tooltip("Animate chat input and commands.");
        signsAndBooks.tooltip("Animate text while editing signs and books.");
        otherFields.tooltip("Animate vanilla text fields, including fields reused by mods.");
    }

    public static TypingAnimation current() { return ScrimmyClient.MODULES.find(TypingAnimation.class); }
    @Override protected void onDisable() { TextAnimator.clear(); }
    @Override protected void onSettingChanged(com.scrimmy.client.setting.Setting<?> setting) { TextAnimator.clear(); }
}
