package com.scrimmy.client.basefinding;

import java.util.ArrayList;
import java.util.List;

/** Worker input is a private byte volume: no Minecraft world, section, or state references. */
public final class GeodeAnalysis {
    public static final byte UNKNOWN = 0,
            NATURAL = 1,
            AIR = 2,
            WATER = 3,
            BUDDING = 4,
            SHELL = 5,
            MAN_MADE = 6;
    public static final int STAGE = 16;
    // Direction order agrees with Direction.get3DDataValue(): down, up, north, south, west, east.
    private static final int[] DX = {0, 0, 0, 0, -1, 1},
            DY = {-1, 1, 0, 0, 0, 0},
            DZ = {0, 0, -1, 1, 0, 0};

    public record Volume(
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth,
            byte[] blocks,
            int chunkX,
            int chunkZ,
            int radius,
            boolean manMadeEnabled,
            int[] buddingStarts) {
        int index(int x, int y, int z) {
            return (y * depth + z) * width + x;
        }

        int at(int x, int y, int z) {
            return x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth
                    ? UNKNOWN
                    : blocks[index(x, y, z)] & 255;
        }
    }

    public record Geode(int x, int y, int z, GeodeScoring.Stats stats) {
        public long id() {
            return ((long) x & 0x3ffffffL) << 38
                    | ((long) z & 0x3ffffffL) << 12
                    | ((long) y & 0xfffL);
        }
    }

    private GeodeAnalysis() {}

    public static List<Geode> analyze(Volume v) {
        byte[] visited = new byte[v.blocks.length];
        int[] queue = new int[v.blocks.length];
        List<Geode> result = new ArrayList<>();
        // Production seeds come only from sections passing the budding palette test. The
        // remaining volume is a connectivity/face/evidence halo, never a second budding search.
        int starts = v.buddingStarts == null ? v.blocks.length : v.buddingStarts.length;
        for (int seed = 0; seed < starts; seed++) {
            int start = v.buddingStarts == null ? seed : v.buddingStarts[seed];
            if (visited[start] != 0 || v.blocks[start] != BUDDING) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = 1;
            int budding = 0, exposedBudding = 0, exposed = 0, empty = 0;
            int[] stages = new int[4];
            int anchor = Integer.MAX_VALUE;
            boolean target = false, incomplete = false, made = false;
            while (head < tail) {
                if ((head & 1023) == 0 && Thread.currentThread().isInterrupted()) return List.of();
                int index = queue[head++],
                        x = index % v.width,
                        z = (index / v.width) % v.depth,
                        y = index / (v.width * v.depth);
                int kind = v.blocks[index];
                if (kind == BUDDING) {
                    budding++;
                    anchor = Math.min(anchor, index);
                    int before = exposed;
                    if ((v.x + x >> 4) == v.chunkX && (v.z + z >> 4) == v.chunkZ) target = true;
                    for (int d = 0; d < 6; d++) {
                        int neighbor = v.at(x + DX[d], y + DY[d], z + DZ[d]);
                        if (neighbor == UNKNOWN) incomplete = true;
                        if (neighbor == AIR || neighbor == WATER) {
                            exposed++;
                            empty++;
                        } else if (neighbor >= STAGE && (neighbor - STAGE) / 4 == d) {
                            exposed++;
                            stages[(neighbor - STAGE) % 4]++;
                        }
                    }
                    if (exposed > before) exposedBudding++;
                    if (v.manMadeEnabled && !made)
                        search:
                        for (int oy = -v.radius; oy <= v.radius; oy++)
                            for (int oz = -v.radius; oz <= v.radius; oz++)
                                for (int ox = -v.radius; ox <= v.radius; ox++)
                                    if (ox * ox + oy * oy + oz * oz <= v.radius * v.radius
                                            && v.at(x + ox, y + oy, z + oz) == MAN_MADE) {
                                        made = true;
                                        break search;
                                    }
                }
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d], neighbor = v.at(nx, ny, nz);
                    if (neighbor == UNKNOWN) {
                        incomplete = true;
                        continue;
                    }
                    if (neighbor != BUDDING && neighbor != SHELL) continue;
                    int next = v.index(nx, ny, nz);
                    if (visited[next] == 0) {
                        visited[next] = 1;
                        queue[tail++] = next;
                    }
                }
            }
            // A shell crossing the snapshot/loading boundary cannot yet be scored as a whole geode.
            if (!target || incomplete || anchor == Integer.MAX_VALUE || exposed == 0) continue;
            int ax = anchor % v.width,
                    az = anchor / v.width % v.depth,
                    ay = anchor / (v.width * v.depth);
            result.add(
                    new Geode(
                            v.x + ax,
                            v.y + ay,
                            v.z + az,
                            new GeodeScoring.Stats(
                                    budding,
                                    exposedBudding,
                                    exposed,
                                    empty,
                                    stages[0],
                                    stages[1],
                                    stages[2],
                                    stages[3],
                                    made)));
            if (result.size() == 256) break;
        }
        return List.copyOf(result);
    }
}
