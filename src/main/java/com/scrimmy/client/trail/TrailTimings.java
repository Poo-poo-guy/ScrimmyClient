package com.scrimmy.client.trail;

import java.util.concurrent.atomic.AtomicLongArray;

/** Temporary opt-in profiling: -Dscrimmy.drillTrailTimings=true. No frame strings when off. */
public final class TrailTimings {
    public static final boolean ENABLED = Boolean.getBoolean("scrimmy.drillTrailTimings");
    public static final int SNAPSHOT = 0, DETECTION = 1, CHAINING = 2, GEOMETRY = 3, RENDER = 4;
    private static final String[] NAMES = {"snapshot", "detection", "chaining", "geometry", "render"};
    private static final AtomicLongArray TOTAL = new AtomicLongArray(5), COUNT = new AtomicLongArray(5), MAX = new AtomicLongArray(5);
    private static long nextLog;
    private TrailTimings() {}
    public static long start() { return ENABLED ? System.nanoTime() : 0; }
    public static void end(int stage, long start) {
        if (!ENABLED) return;
        record(stage,System.nanoTime()-start);
    }
    public static void record(int stage,long nanos) {
        if(!ENABLED)return;
        TOTAL.addAndGet(stage, nanos); COUNT.incrementAndGet(stage);
        MAX.accumulateAndGet(stage, nanos, Math::max);
    }
    public static void log() {
        if (!ENABLED || System.nanoTime() < nextLog) return;
        nextLog = System.nanoTime() + 5_000_000_000L;
        for (int stage = 0; stage < NAMES.length; stage++) {
            long count = COUNT.getAndSet(stage, 0), total = TOTAL.getAndSet(stage, 0), max = MAX.getAndSet(stage, 0);
            if (count > 0) System.getLogger("Scrimmy DrillTrail timings").log(System.Logger.Level.INFO,
                    String.format(java.util.Locale.ROOT, "%s: n=%d mean=%.3f ms max=%.3f ms", NAMES[stage], count, total / (count * 1e6), max / 1e6));
        }
    }
}
