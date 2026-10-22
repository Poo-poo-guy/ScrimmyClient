package com.scrimmy.client.particle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleMathTest {
    @Test void everyEasingHasContinuousInvisibleEndpoints() {
        for (String curve : new String[]{"Linear", "Ease-out", "Ease-in-out", "Back", "Elastic"}) {
            assertEquals(0, ParticleMath.scale(curve, 0, 1, .12, 0), 1e-9);
            assertEquals(0, ParticleMath.scale(curve, 1, 1, .12, 0), 1e-9);
            assertEquals(0, ParticleMath.alpha(curve, 0, 1, .12, 1, .5), 1e-9);
            assertEquals(0, ParticleMath.alpha(curve, 1, 1, .12, 1, .5), 1e-9);
            for (int i = 0; i <= 100; i++) {
                double a = ParticleMath.alpha(curve, i / 100.0, 1, .12, 1, .5);
                assertTrue(a >= 0 && a <= 1);
                assertTrue(ParticleMath.scale(curve, i / 100.0, 1, .12, 0) >= 0);
            }
        }
    }
    @Test void particlesExpireAndTheSameObjectsCanBeReused() {
        ParticlePool pool = new ParticlePool();
        var first = pool.acquire(16); first.reset(1, 2, 3); first.lifetime = .2; first.vx = 1;
        pool.tick(.05, "Radial burst", 0, -1);
        assertEquals(1.05, first.x, 1e-9);
        assertTrue(first.y > 2);
        assertEquals(1, first.trailCount);
        for (int i = 0; i < 4; i++) pool.tick(.05, "Radial burst", 0, -1);
        assertEquals(0, pool.size());
        assertSame(first, pool.acquire(16));
    }
    @Test void hardCapAndEveryMotionStayFinite() {
        ParticlePool pool = new ParticlePool();
        for (String mode : new String[]{"Radial burst", "Upward fountain", "Spiral", "Explosion", "Orbit"}) {
            pool.clear();
            for (int i = 0; i < 20; i++) {
                var p = pool.acquire(16);
                if (i >= 16) { assertNull(p); continue; }
                p.reset(0, 0, 0); p.vx = 1; p.vy = 2; p.vz = -1; p.lifetime = 4; p.orbitRadius = .5;
            }
            for (int tick = 0; tick < 40; tick++) pool.tick(.05, mode, 2, -.5);
            for (int i = 0; i < pool.size(); i++) {
                var p = pool.get(i);
                assertTrue(Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z));
                assertEquals(ParticlePool.TRAIL, p.trailCount);
            }
            pool.cap(4); assertEquals(4, pool.size());
        }
    }
}
