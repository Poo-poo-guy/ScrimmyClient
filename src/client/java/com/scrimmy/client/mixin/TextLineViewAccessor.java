package com.scrimmy.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The 1.21.11 line record is package private; avoid widening Minecraft's API. */
@Mixin(targets = "net.minecraft.client.gui.components.MultilineTextField$StringView")
public interface TextLineViewAccessor {
    @Accessor("beginIndex") int scrimmy$beginIndex();
}
