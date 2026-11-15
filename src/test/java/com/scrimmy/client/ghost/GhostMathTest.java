package com.scrimmy.client.ghost;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GhostMathTest {
    @Test void lifetimeFadeUsesTheShorterOfTenPercentAndTenSeconds() {
        assertEquals(1, GhostMath.fade(108, 120, 10, 128, 256));
        assertEquals(1, GhostMath.fade(110, 120, 10, 128, 256));
        assertEquals(.5, GhostMath.fade(115, 120, 10, 128, 256), 1e-9);
        assertEquals(0, GhostMath.fade(120, 120, 10, 128, 256));
        assertEquals(1, GhostMath.fade(9, 10, 10, 128, 256));
        assertEquals(.5, GhostMath.fade(9.5, 10, 10, 128, 256), 1e-9);
        assertEquals(0, GhostMath.fade(10, 10, 10, 128, 256));
    }
    @Test void distanceFadeIsGentleAndHandlesFadeBeyondTheDrawLimit() {
        assertEquals(1, GhostMath.fade(0, 120, 128, 128, 256));
        assertEquals(.8, GhostMath.fade(0, 120, 192, 128, 256), 1e-9);
        assertEquals(.6, GhostMath.fade(0, 120, 256, 128, 256), 1e-9);
        assertEquals(0, GhostMath.fade(0, 120, 257, 128, 256));
        assertEquals(1, GhostMath.fade(0, 120, 32, 128, 32));
    }
    @Test void perspectiveProjectionRejectsBehindOffscreenAndNearPlanePoints() {
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(90), 1, .05f, 1024);
        Vector4f clip = new Vector4f();
        assertTrue(GhostMath.project(projection, 0, 0, -10, clip));
        assertEquals(0, clip.x / clip.w); assertEquals(0, clip.y / clip.w);
        assertFalse(GhostMath.project(projection, 0, 0, 10, clip));
        assertFalse(GhostMath.project(projection, 0, 0, 0, clip));
        assertFalse(GhostMath.project(projection, 0, 0, -.01f, clip));
        assertFalse(GhostMath.project(projection, 20, 0, -10, clip));
        assertFalse(GhostMath.project(projection, 0, 20, -10, clip));
        assertTrue(GhostMath.project(projection, 2, 2, -10, clip));
        assertTrue(clip.x / clip.w > 0 && clip.y / clip.w > 0, "Positive world up must map to the upper screen half");
        Matrix4f turned = new Matrix4f(projection).rotateY((float)Math.PI);
        assertTrue(GhostMath.project(turned, 0, 0, 10, clip));
        assertFalse(GhostMath.project(turned, 0, 0, -10, clip));
    }
    @Test void agingReachesGrayAndOpacityPreservesTheChosenAlpha() {
        assertEquals(0xD9D8F8FF, GhostMath.color(0xD9D8F8FF, 0, 1));
        assertEquals(0xD9000000 | GhostMath.OLD_COLOR, GhostMath.color(0xD9D8F8FF, 1, 1));
        assertEquals(0, GhostMath.color(0xD9D8F8FF, .5, 0) >>> 24);
        assertEquals(108, GhostMath.color(0xD9D8F8FF, .5, .5) >>> 24);
    }
}
