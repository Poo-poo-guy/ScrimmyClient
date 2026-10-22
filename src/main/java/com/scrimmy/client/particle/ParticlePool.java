package com.scrimmy.client.particle;

/** All particles and trail samples are allocated once. Tick integration is independent of rendering FPS. */
public final class ParticlePool {
    public static final int CAPACITY = 4096, TRAIL = 16;
    public static final class Particle {
        public double x, y, z, oldX, oldY, oldZ, vx, vy, vz, age, lifetime, size, baseSize, angle, oldAngle, spin, phase;
        public double originX, originY, originZ, orbitRadius;
        public int shape, targetId, trailHead, trailCount;
        public double colorSeed;
        public final double[] trailX = new double[TRAIL], trailY = new double[TRAIL], trailZ = new double[TRAIL];
        public void reset(double x, double y, double z) {
            this.x = oldX = originX = x; this.y = oldY = originY = y; this.z = oldZ = originZ = z;
            age = 0; trailHead = trailCount = 0;
        }
    }
    private final Particle[] particles = new Particle[CAPACITY];
    private int count;
    public ParticlePool() { for (int i = 0; i < CAPACITY; i++) particles[i] = new Particle(); }
    public int size() { return count; }
    public Particle get(int index) { return particles[index]; }
    public void clear() { count = 0; }
    public void cap(int cap) { count = Math.min(count, Math.max(0, Math.min(CAPACITY, cap))); }
    public Particle acquire(int cap) {
        cap(cap);
        if (count >= cap || count >= CAPACITY) return null;
        return particles[count++];
    }
    public void tick(double dt, String mode, double drag, double gravity) {
        double damping = Math.exp(-Math.max(0, drag) * dt * (mode.equals("Explosion") ? 3 : 1));
        for (int i = 0; i < count;) {
            Particle p = particles[i];
            p.age += dt;
            if (p.age >= p.lifetime) { particles[i] = particles[--count]; particles[count] = p; continue; }
            p.oldX = p.x; p.oldY = p.y; p.oldZ = p.z; p.oldAngle = p.angle;
            p.trailHead = (p.trailHead + 1) % TRAIL;
            p.trailX[p.trailHead] = p.x; p.trailY[p.trailHead] = p.y; p.trailZ[p.trailHead] = p.z;
            p.trailCount = Math.min(TRAIL, p.trailCount + 1);
            p.vx *= damping; p.vy = p.vy * damping - gravity * dt; p.vz *= damping;
            if (mode.equals("Orbit")) {
                double a = p.phase + p.age * 3;
                p.x = p.originX + Math.cos(a) * p.orbitRadius;
                p.z = p.originZ + Math.sin(a) * p.orbitRadius;
                p.y = p.originY + Math.sin(a * 1.5) * p.orbitRadius * .4 - gravity * p.age * p.age * .5;
            } else {
                double vx = p.vx, vz = p.vz;
                if (mode.equals("Spiral")) {
                    double a = p.age * 4, c = Math.cos(a), s = Math.sin(a);
                    vx = p.vx * c - p.vz * s; vz = p.vx * s + p.vz * c;
                }
                p.x += vx * dt; p.y += p.vy * dt; p.z += vz * dt;
            }
            p.angle += p.spin * dt;
            i++;
        }
    }
}
