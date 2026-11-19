package com.scrimmy.client.animation;

/** Allocation-free, analytic damped motion plus reversible eased timelines. Units are seconds. */
public final class Animation {
    private double value, velocity, target, start, elapsed, duration, delay, correction;
    private Easing curve = Easing.OUT_QUINT;
    public Animation() { this(0); }
    public Animation(double initial) { value = target = start = initial; }
    public float value() { return (float)value; }
    public double velocity() { return velocity; }
    public void snap(double next) { value = start = target = next; velocity = elapsed = delay = 0; }

    /** Exact solution, so a long frame needs no unstable Euler integration or delta clamp. */
    public float spring(double next, double dt, double frequency, double damping) {
        target = next;
        double offset = value - target;
        if (damping >= 1) {
            double c = velocity + frequency * offset, decay = Math.exp(-frequency * dt);
            value = target + (offset + c * dt) * decay;
            velocity = (velocity - frequency * c * dt) * decay;
        } else {
            double a = frequency * damping, b = frequency * Math.sqrt(1 - damping * damping);
            double c = (velocity + a * offset) / b, decay = Math.exp(-a * dt);
            double cos = Math.cos(b * dt), sin = Math.sin(b * dt);
            value = target + decay * (offset * cos + c * sin);
            velocity = decay * ((c * b - a * offset) * cos - (offset * b + a * c) * sin);
        }
        if (Math.abs(value - target) < .00001 && Math.abs(velocity) < .0001) snap(target);
        return value();
    }

    /** Retarget from the current value. Delay belongs to the initial/reversed wave, never a reset. */
    public float tween(double next, double dt, double seconds, Easing easing, double wait) {
        if (target != next || duration == 0) {
            boolean reversing = duration > 0 && elapsed < duration;
            start = value; target = next; elapsed = 0; duration = Math.max(.001, seconds);
            correction = reversing ? velocity - (target - start) * easing.slope(0) / duration : 0;
            curve = easing; delay = Math.max(0, wait);
        }
        if (delay > 0) { double used = Math.min(delay, dt); delay -= used; dt -= used; }
        elapsed = Math.min(duration, elapsed + dt);
        double t = elapsed / duration, u = 1 - t;
        value = start + (target - start) * curve.apply(t) + correction * duration * t * u * u;
        velocity = (target - start) * curve.slope(t) / duration + correction * u * (1 - 3 * t);
        if (elapsed >= duration) { value = target; velocity = 0; }
        return value();
    }
}
