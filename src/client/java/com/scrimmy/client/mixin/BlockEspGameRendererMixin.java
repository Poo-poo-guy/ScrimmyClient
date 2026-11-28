package com.scrimmy.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.scrimmy.client.render.BlockEspCamera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the world projection without changing Minecraft or any other module's matrices. */
@Mixin(GameRenderer.class)
abstract class BlockEspGameRendererMixin {
    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getProjectionMatrix(F)Lorg/joml/Matrix4f;"))
    private Matrix4f scrimmy$blockEspProjection(Matrix4f projection) {
        BlockEspCamera.captureProjection(projection);
        return projection;
    }
}
