package com.scrimmy.client.camera;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreecamMotionTest {
    @Test void forwardAndStrafeFollowMinecraftYawAndPitch() {
        FreecamMotion camera = new FreecamMotion();
        camera.reset(0, 0, 0, 0, 0); camera.move(1, 0, 0, 1);
        assertPosition(camera, 0, 0, 1);
        camera.reset(0, 0, 0, 90, 0); camera.move(1, 0, 0, 1);
        assertPosition(camera, -1, 0, 0);
        camera.reset(0, 0, 0, 90, 0); camera.move(0, 1, 0, 1);
        assertPosition(camera, 0, 0, 1);
        camera.reset(0, 0, 0, 0, -90); camera.move(1, 0, 0, 1);
        assertPosition(camera, 0, 1, 0);
        camera.reset(0, 0, 0, 0, 90); camera.move(1, 0, 0, 1);
        assertPosition(camera, 0, -1, 0);
    }
    @Test void diagonalMovementIsNormalizedAndVerticalKeysRemainWorldAligned() {
        FreecamMotion camera = new FreecamMotion();
        camera.reset(0, 0, 0, 0, 0); camera.move(1, 1, 1, 8);
        assertEquals(8, Math.sqrt(camera.x*camera.x + camera.y*camera.y + camera.z*camera.z), 1e-9);
        camera.reset(0, 0, 0, 67, 45); camera.move(0, 0, 1, 2);
        assertPosition(camera, 0, 2, 0);
        camera.move(0, 0, -1, 2); assertPosition(camera, 0, 0, 0);
        camera.move(0, 0, 0, 100); assertPosition(camera, 0, 0, 0);
    }
    @Test void movementHasNoCollisionOrWorldBoundsAndInterpolatesWithoutChangingState() {
        FreecamMotion camera = new FreecamMotion();
        camera.reset(30_000_000, 64, -30_000_000, 0, 0);
        camera.beginTick(); camera.move(1, 0, 0, 10);
        assertEquals(-30_000_000, camera.renderZ(0));
        assertEquals(-29_999_995, camera.renderZ(.5f));
        assertEquals(-29_999_990, camera.renderZ(1));
        assertEquals(-29_999_990, camera.z);
        camera.beginTick();
        assertEquals(camera.z, camera.renderZ(0));
        camera.reset(1, 2, 3, 0, 0);
        assertEquals(1, camera.renderX(.5f)); assertEquals(2, camera.renderY(.5f)); assertEquals(3, camera.renderZ(.5f));
    }
    @Test void mouseSensitivityMatchesVanillaAndPitchCannotFlip() {
        FreecamMotion camera = new FreecamMotion();
        camera.reset(1, 2, 3, 0, 0); camera.turn(100, -100);
        assertEquals(15, camera.yaw, 1e-5); assertEquals(-15, camera.pitch, 1e-5);
        camera.turn(0, 10_000); assertEquals(90, camera.pitch);
        camera.turn(0, -20_000); assertEquals(-90, camera.pitch);
        assertPosition(camera, 1, 2, 3);
    }
    @Test void fractionalScrollAccumulatesAndResetDropsPendingScroll() {
        FreecamMotion camera = new FreecamMotion();
        assertEquals(0, camera.scrollSteps(.25)); assertEquals(0, camera.scrollSteps(.25));
        assertEquals(1, camera.scrollSteps(.5));
        assertEquals(0, camera.scrollSteps(-.5)); assertEquals(-1, camera.scrollSteps(-.5));
        assertEquals(0, camera.scrollSteps(Double.NaN)); assertEquals(0, camera.scrollSteps(Double.POSITIVE_INFINITY));
        camera.scrollSteps(.75); camera.reset(0, 0, 0, 0, 0);
        assertEquals(0, camera.scrollSteps(.25));
    }
    @Test void scrollSpeedsUpAndDownIncludingBothLimits() {
        assertEquals(10, FreecamMotion.scrollSpeed(8, 1), 1e-9);
        assertEquals(6.4, FreecamMotion.scrollSpeed(8, -1), 1e-9);
        assertTrue(FreecamMotion.scrollSpeed(FreecamMotion.MIN_SPEED, 1) > FreecamMotion.MIN_SPEED);
        assertTrue(FreecamMotion.scrollSpeed(FreecamMotion.MAX_SPEED, -1) < FreecamMotion.MAX_SPEED);
        assertEquals(FreecamMotion.MIN_SPEED, FreecamMotion.scrollSpeed(8, -1000));
        assertEquals(FreecamMotion.MAX_SPEED, FreecamMotion.scrollSpeed(8, 1000));
        assertEquals(8, FreecamMotion.scrollSpeed(8, 0));
    }
    private void assertPosition(FreecamMotion camera, double x, double y, double z) {
        assertEquals(x, camera.x, 1e-8); assertEquals(y, camera.y, 1e-8); assertEquals(z, camera.z, 1e-8);
    }
}
