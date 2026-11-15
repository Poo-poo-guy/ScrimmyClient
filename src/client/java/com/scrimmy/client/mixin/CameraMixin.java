package com.scrimmy.client.mixin;

import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void scrimmy$freecam(Level level, Entity entity, boolean thirdPerson, boolean mirrored, float partial, CallbackInfo ci) {
        Freecam module = Freecam.current();
        if (module == null || !module.active()) return;
        var camera = module.camera;
        // Apply after vanilla's third-person collision checks so walls cannot push the camera back.
        setPosition(camera.renderX(partial), camera.renderY(partial), camera.renderZ(partial));
        setRotation(camera.yaw, camera.pitch);
        detached = true; // Vanilla now draws the local player's body from the detached view.
    }
}
