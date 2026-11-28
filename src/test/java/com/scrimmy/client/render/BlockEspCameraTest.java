package com.scrimmy.client.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEspCameraTest {
    @Test void captureCopiesProjectionBeforeWalkingBobAndHurtShake() {
        Matrix4f clean = new Matrix4f().perspective((float)Math.toRadians(75), 16f/9, .05f, 512);
        Matrix4f minecraftProjection = new Matrix4f(clean);
        BlockEspCamera.captureProjection(minecraftProjection);
        minecraftProjection.translate(.15f, -.12f, 0).rotateZ(.1f).rotateX(.06f);
        BlockEspCamera frame = new BlockEspCamera();
        frame.update(new Quaternionf(), 0, 64, 0, 0, 62, 0, 0, 64, 0, false);
        assertTrue(frame.projection.equals(clean, .000001f));
        assertFalse(frame.projection.equals(minecraftProjection, .000001f));
    }
    @Test void crosshairRemainsExactlyOnCameraAxisAcrossMovementAndRotation() {
        BlockEspCamera frame = new BlockEspCamera();
        for (int step = 0; step <= 360; step++) {
            float yaw = (float)Math.toRadians(step), pitch = (float)Math.sin(yaw) * 1.4f;
            Quaternionf orientation = new Quaternionf().rotationYXZ(yaw, pitch, 0);
            // Include fractional motion, changing eye heights/poses, and detached-camera offsets.
            double cx = 100.3 + step*.01, cy = 65.7 + Math.sin(yaw)*.8, cz = -45.1 + step*.02;
            frame.update(orientation, cx, cy, cz, cx-4, cy-1.62, cz+2, 96, 64, -48, false);
            Vector3f eye = new Vector3f(frame.tracerStart).mulPosition(frame.modelView);
            assertEquals(0, eye.x, .00001); assertEquals(0, eye.y, .00001); assertEquals(-.15, eye.z, .00001);
        }
    }
    @Test void feetInterpolateAtTheRenderPartialAndUseTheSameOffsetAsBlockCenters() {
        BlockEspCamera frame = new BlockEspCamera();
        Quaternionf orientation = new Quaternionf().rotationYXZ(1.7f, -.6f, 0);
        for (float partial : new float[]{0, .1f, .25f, .5f, .9f, 1}) {
            double x = BlockEspCamera.interpolate(-10.8, -10.4, partial);
            double y = BlockEspCamera.interpolate(64, 63.7, partial);
            double z = BlockEspCamera.interpolate(20.2, 20.8, partial);
            frame.update(orientation, x, y+1.25, z, x, y, z, -16, 64, 16, true);
            assertEquals(x+16, frame.tracerStart.x, .000001);
            assertEquals(y-64, frame.tracerStart.y, .000001);
            assertEquals(z-16, frame.tracerStart.z, .000001);
            Vector3f actual = new Vector3f(frame.tracerStart).mulPosition(frame.modelView);
            Vector3f expected = new Vector3f(0, -1.25f, 0).mulDirection(frame.view);
            assertEquals(expected.x, actual.x, .00001); assertEquals(expected.y, actual.y, .00001); assertEquals(expected.z, actual.z, .00001);
        }
    }
}
