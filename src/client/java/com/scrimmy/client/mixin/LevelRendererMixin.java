package com.scrimmy.client.mixin;

import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;cullTerrain(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V"), index = 2)
    private boolean scrimmy$seeFromInsideWalls(boolean spectator) {
        Freecam module = Freecam.current();
        // Vanilla's spectator culling path keeps the scene visible from inside a solid block.
        return spectator || (module != null && module.active());
    }
}
