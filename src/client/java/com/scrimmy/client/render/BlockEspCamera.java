package com.scrimmy.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** BlockESP-only frame transforms, captured before Minecraft adds bob/hurt to its projection. */
public final class BlockEspCamera {
    private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
    public final Matrix4f projection = new Matrix4f(), view = new Matrix4f(), modelView = new Matrix4f();
    public final Vector3f tracerStart = new Vector3f();

    public static void captureProjection(Matrix4fc projection) { WORLD_PROJECTION.set(projection); }

    public void update(Quaternionfc orientation, double cameraX, double cameraY, double cameraZ,
                       double feetX, double feetY, double feetZ, int originX, int originY, int originZ, boolean feet) {
        projection.set(WORLD_PROJECTION);
        // Camera orientation is already interpolated for this frame and contains no bob/hurt effects.
        view.rotation(orientation).transpose();
        modelView.set(view).translate((float)(originX-cameraX), (float)(originY-cameraY), (float)(originZ-cameraZ));
        if (feet) tracerStart.set((float)(feetX-originX), (float)(feetY-originY), (float)(feetZ-originZ));
        else tracerStart.set(0, 0, -.15f).rotate(orientation)
            .add((float)(cameraX-originX), (float)(cameraY-originY), (float)(cameraZ-originZ));
    }

    public static double interpolate(double previous, double current, float partial) {
        return previous + (current - previous) * partial;
    }
}
