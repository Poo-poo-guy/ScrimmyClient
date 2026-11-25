package com.scrimmy.client.basefinding;

import com.scrimmy.client.trail.TrailMath;
import com.scrimmy.client.trail.TunnelAnalysis.Sample;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.*;

/** Immutable loaded-data evidence and worker-only math. Scores are heuristics, not probabilities. */
public final class HumanActivityAnalysis {
    public static final int LEAK_RADIUS = 8;
    public record Terrain(long key, long[] exposed, byte[] rarity, double exposedRarity,
                          int flattened, int terraces, int pillars, int casts) {}
    public record Fragment(int size, int nonNatural) {}
    public record Features(double exposed, int tunnels, int leakedBlocks, int leakedNonNatural,
                           double leakedRarity, double growth, int flattened, int terraces,
                           int pillars, int casts, List<Fragment> fragments) {
        public double surface() { return flattened + terraces + pillars * 4.0 + casts * 2.0; }
    }
    public record Weights(double exposed, double tunnels, double leaks, double growth, double surface,
                          double neighbors) {}
    public record Ranked(long key, Features features, double rawScore, double score) {
        public int x() { return (int)key; }
        public int z() { return (int)(key >>> 32); }
    }
    private HumanActivityAnalysis() {}

    /** Rarity reflects ambiguity in natural structures, not an assertion of player placement. */
    public static int rarity(String id) {
        if (id.endsWith("_sign") || id.endsWith("_hanging_sign") || id.contains("concrete")
                || id.contains("stained_glass") || id.equals("glass") || id.equals("glass_pane")
                || id.endsWith("_bed") || id.equals("crafting_table") || id.equals("redstone_wire")
                || id.equals("repeater") || id.equals("comparator") || id.equals("redstone_lamp")) return 5;
        if (id.contains("torch") || id.endsWith("rail") || id.endsWith("_planks")
                || id.contains("bricks") || id.equals("bricks") || id.contains("polished_")
                || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_door")
                || id.endsWith("_trapdoor") || id.endsWith("_stairs") || id.endsWith("_slab")
                || id.endsWith("_button") || id.endsWith("_pressure_plate") || id.contains("wool")
                || id.equals("ladder") || id.equals("scaffolding") || id.equals("barrel")
                || id.equals("chest") || id.equals("trapped_chest") || id.endsWith("shulker_box")
                || id.equals("furnace") || id.equals("hopper") || id.equals("dispenser")
                || id.equals("dropper") || id.equals("iron_bars") || id.contains("lantern")) return 2;
        return 0;
    }

    public static Map<Long, Features> features(Map<Long, Terrain> terrain, List<Sample> samples,
                                              Map<Long, Double> growth) {
        Long2ByteOpenHashMap exposed = new Long2ByteOpenHashMap();
        for (Terrain t : terrain.values()) for (int i = 0; i < t.exposed.length; i++)
            exposed.put(t.exposed[i], t.rarity[i]);
        LongOpenHashSet shell = new LongOpenHashSet(), candidates = new LongOpenHashSet();
        Map<Long, Integer> tunnelCounts = new HashMap<>();
        // The detector's cross section is a 3x3 hole; max(|a|,|b|)==2 is its one-block wall ring.
        // Mask the union of ALL hole/shell slabs so crossing shafts cannot count each other's walls.
        for (Sample s : samples) {
            if (Thread.currentThread().isInterrupted()) return Map.of();
            tunnelCounts.merge(TrailMath.chunk(s.x() >> 4, s.z() >> 4), 1, Integer::sum);
            for (int axis = 0; axis < 3; axis++) if ((s.axes() & 1 << axis) != 0) {
                for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++)
                    for (int along = -1; along <= 1; along++) {
                        long pos = offset(s, axis, a, b, along);
                        if (exposed.containsKey(pos)) shell.add(pos);
                    }
                for (int a = -LEAK_RADIUS; a <= LEAK_RADIUS; a++)
                    for (int b = -LEAK_RADIUS; b <= LEAK_RADIUS; b++) {
                        if (Math.max(Math.abs(a), Math.abs(b)) <= 2) continue;
                        for (int along = -1; along <= 1; along++) {
                            long pos = offset(s, axis, a, b, along);
                            if (exposed.containsKey(pos)) candidates.add(pos);
                        }
                    }
            }
        }
        candidates.removeAll(shell);
        Map<Long, List<Fragment>> fragments = new HashMap<>();
        Map<Long, int[]> leakedCounts = new HashMap<>();
        Map<Long, Double> leakedRarity = new HashMap<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        int visited = 0;
        while (!candidates.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) return Map.of();
            long seed = candidates.iterator().nextLong(); candidates.remove(seed); queue.enqueue(seed);
            Map<Long, int[]> portions = new HashMap<>();
            while (!queue.isEmpty()) {
                if ((++visited & 1023) == 0 && Thread.currentThread().isInterrupted()) return Map.of();
                long pos = queue.dequeueLong();
                int x = TrailMath.x(pos), y = TrailMath.y(pos), z = TrailMath.z(pos);
                long owner = TrailMath.chunk(x >> 4, z >> 4);
                int rarity = exposed.get(pos);
                int[] counts = portions.computeIfAbsent(owner, k -> new int[2]);
                counts[0]++; if (rarity > 0) counts[1]++;
                leakedRarity.merge(owner, (double)rarity, Double::sum);
                for (int dir = 0; dir < 6; dir++) {
                    long next = TrailMath.key(x + com.scrimmy.client.trail.TunnelAnalysis.DX[dir],
                            y + com.scrimmy.client.trail.TunnelAnalysis.DY[dir],
                            z + com.scrimmy.client.trail.TunnelAnalysis.DZ[dir]);
                    if (candidates.remove(next)) queue.enqueue(next);
                }
            }
            int size = portions.values().stream().mapToInt(count -> count[0]).sum();
            int nonNatural = portions.values().stream().mapToInt(count -> count[1]).sum();
            // Record the whole connected fragment, but score only the blocks owned by each chunk.
            portions.forEach((key, count) -> {
                fragments.computeIfAbsent(key, k -> new ArrayList<>()).add(new Fragment(size, nonNatural));
                int[] local = leakedCounts.computeIfAbsent(key, k -> new int[2]);
                local[0] += count[0]; local[1] += count[1];
            });
        }
        Map<Long, Features> result = new HashMap<>();
        terrain.forEach((key, t) -> {
            List<Fragment> parts = fragments.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator.comparingInt(Fragment::size).reversed().thenComparingInt(Fragment::nonNatural)).toList();
            int[] local = leakedCounts.getOrDefault(key, new int[2]);
            result.put(key, new Features(t.exposedRarity, tunnelCounts.getOrDefault(key, 0),
                    local[0], local[1],
                    leakedRarity.getOrDefault(key, 0.0), growth.getOrDefault(key, 0.0),
                    t.flattened, t.terraces, t.pillars, t.casts, parts));
        });
        return Map.copyOf(result);
    }
    private static long offset(Sample s, int axis, int a, int b, int along) {
        return switch (axis) {
            case 0 -> TrailMath.key(s.x() + along, s.y() + a, s.z() + b);
            case 1 -> TrailMath.key(s.x() + a, s.y() + along, s.z() + b);
            default -> TrailMath.key(s.x() + a, s.y() + b, s.z() + along);
        };
    }
    public static double score(Features f, Weights w) {
        double evidence = w.exposed * f.exposed / 40 + w.tunnels * f.tunnels / 24.0
                + w.leaks * (f.leakedBlocks / 128.0 + f.leakedRarity / 20)
                + w.growth * f.growth + w.surface * f.surface() / 32;
        return 100 * -Math.expm1(-Math.max(0, evidence));
    }
    public static List<Ranked> rank(Map<Long, Features> features, Weights weights) {
        Map<Long, Double> raw = new HashMap<>();
        features.forEach((k, f) -> raw.put(k, score(f, weights)));
        List<Ranked> result = new ArrayList<>();
        double share = Math.max(0, Math.min(1, weights.neighbors));
        features.forEach((key, f) -> {
            int x = (int)(long)key, z = (int)(key >>> 32); double sum = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                if (dx != 0 || dz != 0) sum += raw.getOrDefault(TrailMath.chunk(x + dx, z + dz), 0.0);
            result.add(new Ranked(key, f, raw.get(key), (1 - share) * raw.get(key) + share * sum / 8));
        });
        result.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::x).thenComparingInt(Ranked::z));
        return List.copyOf(result);
    }
}
