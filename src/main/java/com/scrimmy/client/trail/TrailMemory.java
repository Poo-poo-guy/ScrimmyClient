package com.scrimmy.client.trail;

import java.util.*;

/** Session-scoped bounded history. Unloading a chunk does not remove its trail or visits. */
public final class TrailMemory {
    public static final int MAX_CHUNKS = 2048, MAX_SAMPLES = 65536;
    public static final double VISIT_RADIUS = 2.25;
    private final LinkedHashMap<Long, TunnelAnalysis.Chunk> chunks = new LinkedHashMap<>();
    private final Set<Long> visited = new HashSet<>();
    private int samples;
    public boolean put(TunnelAnalysis.Chunk chunk) {
        if (chunk.edges() != 0) {
            var old = chunks.get(chunk.key());
            List<TunnelAnalysis.Sample> merged = new ArrayList<>();
            if (old != null) for (var sample : old.samples())
                if (!TunnelAnalysis.inEdges(sample.x(), sample.z(), chunk.edges())) merged.add(sample);
            merged.addAll(chunk.samples());
            merged.sort(Comparator.comparingLong(TunnelAnalysis.Sample::key));
            chunk = new TunnelAnalysis.Chunk(chunk.key(), merged);
        }
        if (chunk.equals(chunks.get(chunk.key()))) return false;
        TunnelAnalysis.Chunk previous = chunks.remove(chunk.key());
        if (previous != null) samples -= previous.samples().size();
        chunks.put(chunk.key(), chunk); samples += chunk.samples().size();
        while (chunks.size() > MAX_CHUNKS || samples > MAX_SAMPLES) {
            var it = chunks.values().iterator(); var old = it.next(); it.remove(); samples -= old.samples().size();
            for (var sample : old.samples()) visited.remove(sample.key());
        }
        // Visits to synthetic intersection points are bounded independently as well.
        if (visited.size() > MAX_SAMPLES * 2) visited.clear();
        return true;
    }
    public List<TunnelAnalysis.Chunk> snapshot() { return List.copyOf(chunks.values()); }
    public boolean visited(long key) { return visited.contains(key); }
    public boolean visit(TunnelNetwork.Network network, double x, double y, double z) {
        boolean changed = false;
        int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
        for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
            double ax = bx + dx + .5 - x, ay = by + dy + .5 - y, az = bz + dz + .5 - z;
            if (ax * ax + ay * ay + az * az > VISIT_RADIUS * VISIT_RADIUS) continue;
            long key = TrailMath.key(bx + dx, by + dy, bz + dz);
            if (network.points().contains(key)) changed |= visited.add(key);
        }
        return changed;
    }
    public int chunkCount() { return chunks.size(); }
    public int sampleCount() { return samples; }
    public void clear() { chunks.clear(); visited.clear(); samples = 0; }
}
