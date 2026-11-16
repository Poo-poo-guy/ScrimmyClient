package com.scrimmy.client.basefinding;

import com.scrimmy.client.basefinding.GeodeAnalysis.Geode;
import java.util.*;

/** Relative loaded-area estimate, not a probability that a base exists. Worker-only math. */
public final class BaseLikelihood {
    public static final double SMALL_WEIGHT = .25, MEDIUM_WEIGHT = .5,
            LARGE_WEIGHT = .75, CLUSTER_WEIGHT = 1;
    public static final int LOADED_RADIUS = 9;
    public static final double DISTANCE_FALLOFF = 1.5, GROWN_START = .35,
            UNGROWN_LIMIT = .25, NEGATIVE_WEIGHT = .8, OVERLAP_BONUS = .35;
    public static final int SMOOTH_PASSES = 2;

    public record Chunk(int x, int z, double score, boolean primary) {
        public long key() { return BaseLikelihood.key(x, z); }
    }

    private BaseLikelihood() {}

    public static long key(int x, int z) { return (x & 0xffffffffL) | ((long) z << 32); }

    public static double growth(GeodeScoring.Stats s) {
        if (s.exposed() == 0) return 0;
        return (s.small() * SMALL_WEIGHT + s.medium() * MEDIUM_WEIGHT
                + s.large() * LARGE_WEIGHT + s.clusters() * CLUSTER_WEIGHT) / s.exposed();
    }

    public static List<Chunk> estimate(Collection<Geode> input, int centerX, int centerZ, int range) {
        range = Math.max(1, Math.min(32, range));
        // A halo makes smoothing independent of the edge of the user's selected range.
        int radius = range + SMOOTH_PASSES, side = radius * 2 + 1;
        int minX = centerX - radius, minZ = centerZ - radius;
        double[] support = new double[side * side], negative = new double[support.length],
                squares = new double[support.length];
        Set<Long> seen = new HashSet<>();
        for (Geode geode : input) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            // Neighbor chunk snapshots can contain the very same cross-border geode.
            if (!seen.add(geode.id())) continue;
            double g = growth(geode.stats());
            double positive = Math.max(0, (g - GROWN_START) / (1 - GROWN_START));
            double empty = Math.max(0, (UNGROWN_LIMIT - g) / UNGROWN_LIMIT);
            double gx = geode.x() / 16.0, gz = geode.z() / 16.0;
            for (int z = Math.max(minZ, (int) Math.floor(gz) - LOADED_RADIUS);
                    z <= Math.min(minZ + side - 1, (int) Math.floor(gz) + LOADED_RADIUS); z++)
                for (int x = Math.max(minX, (int) Math.floor(gx) - LOADED_RADIUS);
                        x <= Math.min(minX + side - 1, (int) Math.floor(gx) + LOADED_RADIUS); x++) {
                    double distance = Math.hypot(x + .5 - gx, z + .5 - gz);
                    if (distance >= LOADED_RADIUS) continue;
                    double falloff = Math.pow(1 - distance * distance / (LOADED_RADIUS * LOADED_RADIUS), DISTANCE_FALLOFF);
                    int i = (z - minZ) * side + x - minX;
                    double contribution = positive * falloff;
                    support[i] += contribution;
                    squares[i] += contribution * contribution;
                    negative[i] += empty * falloff;
                }
        }
        double[] values = new double[support.length], next = new double[support.length];
        for (int i = 0; i < values.length; i++) {
            // Pairwise overlap excludes self-support: one mature geode cannot earn a bonus.
            double overlap = Math.sqrt(Math.max(0, support[i] * support[i] - squares[i]));
            values[i] = Math.max(0, support[i] + OVERLAP_BONUS * overlap
                    - NEGATIVE_WEIGHT * negative[i]);
        }
        for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                double sum = 0, weight = 0;
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    if (x + dx < 0 || x + dx >= side || z + dz < 0 || z + dz >= side) continue;
                    int w = (dx == 0 ? 2 : 1) * (dz == 0 ? 2 : 1);
                    sum += values[(z + dz) * side + x + dx] * w;
                    weight += w;
                }
                next[z * side + x] = sum / weight;
            }
            double[] swap = values; values = next; next = swap;
        }
        double best = 0;
        int primary = -1;
        for (int z = SMOOTH_PASSES; z < side - SMOOTH_PASSES; z++)
            for (int x = SMOOTH_PASSES; x < side - SMOOTH_PASSES; x++) {
                int i = z * side + x;
                if (values[i] > best) { best = values[i]; primary = i; }
            }
        // All-ungrown input provides no positive evidence; never normalize noise into a base.
        if (best <= 1e-6) return List.of();
        List<Chunk> result = new ArrayList<>();
        for (int z = SMOOTH_PASSES; z < side - SMOOTH_PASSES; z++)
            for (int x = SMOOTH_PASSES; x < side - SMOOTH_PASSES; x++) {
                int i = z * side + x;
                if (values[i] > 1e-6)
                    result.add(new Chunk(minX + x, minZ + z, 100 * values[i] / best, i == primary));
            }
        result.sort(Comparator.comparingDouble(Chunk::score).reversed()
                .thenComparing(c -> !c.primary())
                .thenComparingInt(Chunk::x).thenComparingInt(Chunk::z));
        return List.copyOf(result);
    }
}
