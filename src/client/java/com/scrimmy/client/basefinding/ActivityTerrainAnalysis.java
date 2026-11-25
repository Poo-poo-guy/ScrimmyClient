package com.scrimmy.client.basefinding;

import com.scrimmy.client.trail.TrailMath;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Worker-only face exposure and surface analysis over a loaded-data snapshot with a one-block halo. */
public final class ActivityTerrainAnalysis {
    public static final int WIDTH = 18, OPEN = 1, SOLID = 2, EARTH = 3, COBBLE = 4, CAST = 5, WATER = 6, LAVA = 7;
    private final int x, z, minY, height;
    private final short[] data;
    public ActivityTerrainAnalysis(int chunkX, int chunkZ, int minY, int height, short[] data) {
        this.x = chunkX; this.z = chunkZ; this.minY = minY; this.height = height; this.data = data;
    }
    int at(int x, int y, int z) {
        if (y >= height) return OPEN;
        if (x < 0 || z < 0 || y < 0 || x >= WIDTH || z >= WIDTH) return 0;
        return data[(y*WIDTH+z)*WIDTH+x] & 15;
    }
    boolean open(int kind) { return kind == OPEN || kind == WATER || kind == LAVA; }
    boolean exposed(int x, int y, int z) {
        return open(at(x-1,y,z)) || open(at(x+1,y,z)) || open(at(x,y-1,z))
                || open(at(x,y+1,z)) || open(at(x,y,z-1)) || open(at(x,y,z+1));
    }
    public HumanActivityAnalysis.Terrain analyze() {
        LongArrayList positions = new LongArrayList(); ByteArrayList rarity = new ByteArrayList();
        int[] tops = new int[WIDTH*WIDTH]; Arrays.fill(tops,-1);
        double weighted = 0; int pillars = 0, casts = 0, flat = 0, terrace = 0;
        for (int bz = 1; bz <= 16; bz++) for (int bx = 1; bx <= 16; bx++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            for (int y = height-1; y >= 0; y--) {
                int kind = at(bx,y,bz);
                if (kind == 0 || open(kind)) continue;
                if (tops[bz*WIDTH+bx] == -1) tops[bz*WIDTH+bx] = y;
                if (exposed(bx,y,bz)) {
                    byte weight = (byte)(data[(y*WIDTH+bz)*WIDTH+bx] >> 4);
                    positions.add(TrailMath.key(x*16+bx-1,minY+y,z*16+bz-1)); rarity.add(weight); weighted += weight;
                }
            }
            int top = tops[bz*WIDTH+bx], run = 0, castRun = 0; boolean fluid = false;
            for (int y = top; y >= 0; y--) {
                int kind = at(bx,y,bz), sides = 0;
                if (open(at(bx-1,y,bz))) sides++;
                if (open(at(bx+1,y,bz))) sides++;
                if (open(at(bx,y,bz-1))) sides++;
                if (open(at(bx,y,bz+1))) sides++;
                if ((kind == EARTH || kind == COBBLE) && sides >= 3) run++; else break;
            }
            if (run >= 4) pillars++;
            for (int y = top; y >= 0; y--) {
                int kind = at(bx,y,bz);
                if (kind != COBBLE && kind != CAST) break;
                castRun++;
                for (int d = 0; d < 4; d++) {
                    int near = at(bx+(d==0?-1:d==1?1:0),y,bz+(d==2?-1:d==3?1:0));
                    fluid |= near == LAVA || near == WATER;
                }
            }
            if (castRun >= (fluid ? 4 : 8)) casts++;
        }
        // Broad, level 5x5 patches and straight two-level terraces; isolated flat blocks do not count.
        for (int bz = 3; bz <= 14; bz++) for (int bx = 3; bx <= 14; bx++) {
            int top = tops[bz*WIDTH+bx]; if (top < 0) continue;
            boolean level = true;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                level &= tops[(bz+dz)*WIDTH+bx+dx] == top;
            if (level && at(bx,top,bz) != CAST && at(bx,top+1,bz) == OPEN) flat++;
            for (int axis = 0; axis < 2; axis++) {
                boolean straight = true;
                int first = tops[(bz+(axis==0?0:-1))*WIDTH+bx+(axis==0?-1:0)];
                int last = tops[(bz+(axis==0?0:1))*WIDTH+bx+(axis==0?1:0)];
                if (Math.abs(first-last) != 1) continue;
                for (int along = -2; along <= 2; along++) for (int across = -1; across <= 1; across++) {
                    int nx = bx+(axis==0?across:along), nz = bz+(axis==0?along:across);
                    int expected = across < 0 ? first : across > 0 ? last : top;
                    straight &= tops[nz*WIDTH+nx] == expected;
                }
                if (straight && at(bx,top+1,bz) == OPEN) terrace++;
            }
        }
        return new HumanActivityAnalysis.Terrain(TrailMath.chunk(x,z),positions.toLongArray(),rarity.toByteArray(),weighted,flat,terrace,pillars,casts);
    }
}
