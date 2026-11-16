package com.scrimmy.client.trail;

import java.util.*;

/** Worker-only detection over immutable byte snapshots. No Minecraft/world access. */
public final class TunnelAnalysis {
    public static final int DEFAULT_MINIMUM_LENGTH = 12, TOLERATED_BLOCKS = 1,
            MIN_WALL_BLOCKS = 10, MAX_GAP = 6, DROP_DEPTH = 7, MAX_SAMPLES_PER_CHUNK = 4096;
    public static final byte UNKNOWN = 0, SOLID = 1, AIR = 2, WATER = 3, LAVA = 4, DANGER = 5;
    public static final int HAZARD_LAVA = 1, HAZARD_DROP = 2, HAZARD_BLOCK = 4, HAZARD_DANGER = 8;
    public static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};

    public record Volume(int x, int y, int z, int width, int height, int depth, int chunkX, int chunkZ,
            byte[] blocks, boolean[] scanSections) {
        public int at(int bx, int by, int bz) {
            bx -= x; by -= y; bz -= z;
            return bx < 0 || by < 0 || bz < 0 || bx >= width || by >= height || bz >= depth
                    ? UNKNOWN : blocks[(by * depth + bz) * width + bx];
        }
    }
    /** Reach uses six four-bit ray lengths. Frontier/open-room bits use the same direction order. */
    public record Sample(long key, int axes, int reach, int frontier, int rooms, int hazards) {
        public int x() { return TrailMath.x(key); }
        public int y() { return TrailMath.y(key); }
        public int z() { return TrailMath.z(key); }
        public int reach(int direction) { return reach >>> (direction * 4) & 15; }
    }
    public record Chunk(long key, List<Sample> samples) {
        public Chunk { samples = List.copyOf(samples); }
    }
    private TunnelAnalysis() {}

    public static Chunk analyze(Volume v) {
        List<Sample> result = new ArrayList<>();
        int minX = v.chunkX * 16, minZ = v.chunkZ * 16;
        for (int y = v.y; y < v.y + v.height; y++) {
            if (Thread.currentThread().isInterrupted()) break;
            if (v.scanSections != null && !v.scanSections[(y - v.y) >> 4]) continue;
            for (int z = minZ; z < minZ + 16; z++) for (int x = minX; x < minX + 16; x++) {
                int axes = 0;
                for (int axis = 0; axis < 3; axis++)
                    if (cross(v, x, y, z, axis, true)) axes |= 1 << axis;
                if (axes == 0) continue;
                int reach = 0, frontier = 0, rooms = 0, hazards = hazards(v, x, y, z, axes);
                for (int dir = 0; dir < 6; dir++) {
                    if ((axes & 1 << (dir / 2)) == 0) continue;
                    int length = 0;
                    for (int step = 1; step <= MAX_GAP; step++) {
                        int nx = x + DX[dir] * step, ny = y + DY[dir] * step, nz = z + DZ[dir] * step;
                        if (unknownCross(v, nx, ny, nz, dir / 2)) { frontier |= 1 << dir; break; }
                        boolean open = cross(v, nx, ny, nz, dir / 2, false);
                        if (step <= 3) {
                            int ahead = hazards(v, nx, ny, nz, axes);
                            // A solid end wall is normal; a lava plug or fire ahead still warns
                            // even when that cross-section is too obstructed to continue the trail.
                            hazards |= open ? ahead : ahead & (HAZARD_LAVA | HAZARD_DROP | HAZARD_DANGER);
                        }
                        if (!open) break;
                        length = step;
                        if (!cross(v, nx, ny, nz, dir / 2, true)) rooms |= 1 << dir;
                    }
                    reach |= length << (dir * 4);
                }
                result.add(new Sample(TrailMath.key(x, y, z), axes, reach, frontier, rooms, hazards));
                if (result.size() >= MAX_SAMPLES_PER_CHUNK) return new Chunk(TrailMath.chunk(v.chunkX, v.chunkZ), result);
            }
        }
        return new Chunk(TrailMath.chunk(v.chunkX, v.chunkZ), result);
    }
    private static int at(Volume v, int x, int y, int z, int axis, int a, int b) {
        return switch (axis) {
            case 0 -> v.at(x, y + a, z + b);
            case 1 -> v.at(x + a, y, z + b);
            default -> v.at(x + a, y + b, z);
        };
    }
    private static boolean unknownCross(Volume v, int x, int y, int z, int axis) {
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++)
            if (at(v, x, y, z, axis, a, b) == UNKNOWN) return true;
        return false;
    }
    private static boolean cross(Volume v, int x, int y, int z, int axis, boolean walls) {
        int imperfect = 0;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            int block = at(v, x, y, z, axis, a, b);
            if (block == UNKNOWN || block != AIR && ++imperfect > TOLERATED_BLOCKS) return false;
        }
        if (!walls) return true;
        int solid = 0;
        for (int a = -1; a <= 1; a++) {
            if (at(v, x, y, z, axis, -2, a) == SOLID) solid++;
            if (at(v, x, y, z, axis, 2, a) == SOLID) solid++;
            if (at(v, x, y, z, axis, a, -2) == SOLID) solid++;
            if (at(v, x, y, z, axis, a, 2) == SOLID) solid++;
        }
        return solid >= MIN_WALL_BLOCKS;
    }
    private static int hazards(Volume v, int x, int y, int z, int axes) {
        int flags = 0;
        for (int axis = 0; axis < 3; axis++) if ((axes & 1 << axis) != 0)
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                int block = at(v, x, y, z, axis, a, b);
                if (block == LAVA) flags |= HAZARD_LAVA;
                if (block == SOLID && a == 0 && b == 0) flags |= HAZARD_BLOCK;
                if (block == DANGER) flags |= HAZARD_DANGER;
            }
        if (axes != 0) {
            int depth = 0;
            for (int d = 2; d <= DROP_DEPTH + 1; d++) {
                int below = v.at(x, y - d, z);
                if (below == UNKNOWN || below == SOLID || below == WATER) break;
                if (below == LAVA || below == DANGER) { flags |= HAZARD_LAVA; break; }
                depth++;
            }
            if (depth >= DROP_DEPTH) flags |= HAZARD_DROP;
        }
        return flags;
    }
}
