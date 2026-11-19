package com.scrimmy.client.animation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTest {
    @Test void springIsIndependentOfFrameRateAndLongFrames() {
        for (double damping : new double[]{1, .88}) {
            Animation a = new Animation(), b = new Animation(), dipped = new Animation();
            for (int i = 0; i < 60; i++) a.spring(1, 1.0 / 60, 19, damping);
            for (int i = 0; i < 240; i++) b.spring(1, 1.0 / 240, 19, damping);
            dipped.spring(1, 1, 19, damping);
            assertEquals(a.value(), b.value(), .00001);
            assertEquals(a.value(), dipped.value(), .00001);
            assertTrue(Float.isFinite(dipped.spring(0, 10, 19, damping)));
        }
    }
    @Test void reversalPreservesPositionAndVelocity() {
        Animation a = new Animation();
        a.spring(1, .06, 19, 1);
        float value = a.value(); double velocity = a.velocity();
        assertEquals(value, a.spring(0, 0, 19, 1));
        assertEquals(velocity, a.velocity(), .000001);
        for (int i = 0; i < 120; i++) a.spring(0, 1.0 / 60, 19, 1);
        assertEquals(0, a.value(), .00001);
        a = new Animation();
        a.tween(1, .12, .32, Easing.OUT_QUINT, 0);
        value = a.value(); velocity = a.velocity();
        assertEquals(value, a.tween(0, 0, .32, Easing.OUT_QUINT, 0));
        assertEquals(velocity, a.velocity(), .000001);
        a.tween(0, 2, .32, Easing.OUT_QUINT, 0);
        assertEquals(0, a.value());
    }
    @Test void easedTimelinesAndStaggerConsumeFullElapsedTime() {
        for (Easing curve : Easing.values()) {
            Animation a = new Animation(), b = new Animation();
            for (int i = 0; i < 12; i++) a.tween(1, 1.0 / 60, .32, curve, .025);
            b.tween(1, .2, .32, curve, .025);
            assertEquals(a.value(), b.value(), .000001);
            assertEquals(0, curve.apply(0), .000001);
            assertEquals(1, curve.apply(1), .000001);
        }
        Animation a = new Animation();
        assertEquals(0, a.tween(1, .02, .32, Easing.OUT_CUBIC, .1));
        assertEquals(1, a.tween(1, 1, .32, Easing.OUT_CUBIC, .1));
    }
    @Test void springPersonalityHasOnlySubtleOvershoot() {
        Animation a = new Animation(); float peak = 0;
        for (int i = 0; i < 240; i++) peak = Math.max(peak, a.spring(1, 1.0 / 240, 18, .88));
        assertTrue(peak > 1); assertTrue(peak < 1.01);
    }
}
