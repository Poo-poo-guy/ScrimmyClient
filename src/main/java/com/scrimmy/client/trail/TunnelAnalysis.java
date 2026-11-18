package com.scrimmy.client.trail;

import java.util.*;

/** Worker-only detection over immutable byte snapshots. No Minecraft/world access. */
public final class TunnelAnalysis {
    public static final int DEFAULT_MINIMUM_LENGTH = 12, TOLERATED_BLOCKS = 1,
            MIN_WALL_BLOCKS = 10, MAX_GAP = 6, PLUG_LOOK_AHEAD = 2, GRID_SPACING = 3,
            DROP_DEPTH = 7, SMART_JOINT_WALL_BLOCKS = 9, MAX_SAMPLES_PER_CHUNK = 4096;
    public static final byte UNKNOWN = 0, SOLID = 1, AIR = 2, WATER = 3, LAVA = 4, DANGER = 5;
    public static final int HAZARD_LAVA = 1, HAZARD_DROP = 2, HAZARD_BLOCK = 4, HAZARD_DANGER = 8;
    public static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};

    public record Volume(int x, int y, int z, int width, int height, int depth, int chunkX, int chunkZ,
            byte[] blocks, boolean[] scanSections, long[] airRows, long[] solidRows, int edges) {
        public Volume(int x, int y, int z, int width, int height, int depth, int chunkX, int chunkZ,
                byte[] blocks, boolean[] scanSections) {
            this(x, y, z, width, height, depth, chunkX, chunkZ, blocks, scanSections, null, null, 0);
        }
        Volume bitmap() {
            if (airRows != null) return this;
            long[] air = new long[height * depth], solid = new long[air.length];
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == AIR) air[i / width] |= 1L << (i % width);
                if (blocks[i] == SOLID) solid[i / width] |= 1L << (i % width);
            }
            return new Volume(x,y,z,width,height,depth,chunkX,chunkZ,blocks,scanSections,air,solid,edges);
        }
        long row(long[] rows, int by, int bz) {
            return by < y || by >= y + height || bz < z || bz >= z + depth ? 0 : rows[(by-y)*depth+bz-z];
        }
        public int at(int bx, int by, int bz) {
            bx -= x; by -= y; bz -= z;
            return bx < 0 || by < 0 || bz < 0 || bx >= width || by >= height || bz >= depth
                    ? UNKNOWN : blocks[(by * depth + bz) * width + bx];
        }
    }
    /** Reach uses six four-bit ray lengths; plugs use six two-bit spans. Strict axes/reach
     * let Normal reuse remembered Smart evidence without tolerating its obstructions/joints. */
    public record Sample(long key, int axes, int reach, int frontier, int rooms, int hazards, int plugs, int normalAxes, int normalReach) {
        public Sample(long key, int axes, int reach, int frontier, int rooms, int hazards) {
            this(key, axes, reach, frontier, rooms, hazards, 0, axes, reach);
        }
        public int plug(int direction) { return plugs >>> (direction * 2) & 3; }
        public int x() { return TrailMath.x(key); }
        public int y() { return TrailMath.y(key); }
        public int z() { return TrailMath.z(key); }
        public int reach(int direction) { return reach >>> (direction * 4) & 15; }
    }
    public record Chunk(long key, List<Sample> samples, int edges) {
        public Chunk(long key, List<Sample> samples) { this(key, samples, 0); }
        public Chunk {
            var ordered=new ArrayList<>(samples);ordered.sort(Comparator.comparingLong(Sample::key));
            samples=List.copyOf(ordered);
        }
    }
    private TunnelAnalysis() {}

    public static Chunk analyze(Volume volume) { return analyze(volume, true); }
    public static Chunk analyze(Volume volume, boolean smart) {
        long timing = TrailTimings.start();
        try {
            Volume v = volume.bitmap();
            int minX = v.chunkX * 16, minZ = v.chunkZ * 16;
            byte[] tested = new byte[256 * v.height];
            ArrayDeque<Integer> follow = new ArrayDeque<>();
            // A 3x3 section contains a point on this lattice in both transverse axes.
            // Check its nearby centers, then follow verified hits through bends and stairs.
            for (int y = v.y; y < v.y + v.height; y += GRID_SPACING) {
                if (Thread.currentThread().isInterrupted()) break;
                for (int z = minZ; z < minZ + 16; z += GRID_SPACING)
                    for (int x = minX; x < minX + 16; x += GRID_SPACING) {
                        if (v.at(x,y,z) != AIR) continue;
                        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                            seed(v, x+dx,y+dy,z+dz,smart,tested,follow);
                    }
            }
            List<Integer> hits=new ArrayList<>();
            while (!follow.isEmpty() && !Thread.currentThread().isInterrupted()) {
                int index = follow.removeFirst();hits.add(index);
                int x = minX + index % 16, z = minZ + index / 16 % 16, y = v.y + index / 256;
                for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                    seed(v,x+dx,y+dy,z+dz,smart,tested,follow);
                for (int dir = 0; dir < 6; dir++) {
                    if ((tested[index] & 1 << (dir/2)) == 0) continue;
                    for (int step = 2; step <= MAX_GAP; step++) {
                        int nx=x+DX[dir]*step, ny=y+DY[dir]*step, nz=z+DZ[dir]*step;
                        if (!cross(v,nx,ny,nz,dir/2,false,smart)) break;
                        seed(v,nx,ny,nz,smart,tested,follow);
                    }
                }
            }
            List<Sample> result = new ArrayList<>();
            Collections.sort(hits);
            for (int index : hits) {
                int axes = tested[index] & 7;
                if (axes == 0) continue;
                int x=minX+index%16, z=minZ+index/16%16, y=v.y+index/256;
                int reach=0, normalReach=0, frontier=0, rooms=0, plugs=0, hazards=smart ? hazards(v,x,y,z,axes) : 0;
                for (int dir = 0; dir < 6; dir++) {
                    if ((axes & 1 << (dir/2)) == 0) continue;
                    int length=0, normalLength=0; boolean normalOpen=true;
                    for (int step = 1; step <= MAX_GAP; step++) {
                        int nx=x+DX[dir]*step, ny=y+DY[dir]*step, nz=z+DZ[dir]*step;
                        if (unknownCross(v,nx,ny,nz,dir/2)) { frontier |= 1 << dir; break; }
                        boolean open=cross(v,nx,ny,nz,dir/2,false,smart);
                        if (smart && step <= 3) {
                            int ahead=hazards(v,nx,ny,nz,axes);
                            hazards |= open ? ahead : ahead & (HAZARD_LAVA | HAZARD_DROP | HAZARD_DANGER);
                        }
                        if (!open) break;
                        length=step;
                        if(normalOpen && cross(v,nx,ny,nz,dir/2,false,false))normalLength=step;
                        else normalOpen=false;
                        if (smart && room(v,nx,ny,nz,dir/2)
                                && room(v,nx+DX[dir],ny+DY[dir],nz+DZ[dir],dir/2)) rooms |= 1 << dir;
                    }
                    reach |= length << (dir*4);
                    normalReach |= normalLength << (dir*4);
                    // Only an immediately blocked end is eligible. The graph verifies it is
                    // an endpoint and that the resumed center has the same local heading.
                    if (smart && length == 0) plugs |= plug(v,x,y,z,dir) << (dir*2);
                }
                result.add(new Sample(TrailMath.key(x,y,z),axes,reach,frontier,rooms,hazards,plugs,(tested[index]>>>4)&7,normalReach));
                if (result.size() >= MAX_SAMPLES_PER_CHUNK) break;
            }
            return new Chunk(TrailMath.chunk(v.chunkX,v.chunkZ),result,v.edges);
        } finally { TrailTimings.end(TrailTimings.DETECTION,timing); }
    }
    public static boolean inEdges(int x, int z, int edges) {
        int lx=x & 15, lz=z & 15, band=MAX_GAP+2;
        return edges == 0 || (edges&1)!=0 && lx<band || (edges&2)!=0 && lx>=16-band
                || (edges&4)!=0 && lz<band || (edges&8)!=0 && lz>=16-band;
    }
    private static void seed(Volume v,int x,int y,int z,boolean smart,byte[] tested,ArrayDeque<Integer> follow) {
        int lx=x-v.chunkX*16,lz=z-v.chunkZ*16,ly=y-v.y;
        if (lx<0||lx>=16||lz<0||lz>=16||ly<0||ly>=v.height || !inEdges(x,z,v.edges)) return;
        if (v.scanSections!=null && !v.scanSections[ly>>4]) return;
        int index=(ly*16+lz)*16+lx;
        if (tested[index]!=0) return;
        int axes=0,normalAxes=0;
        for(int axis=0;axis<3;axis++) {
            if(cross(v,x,y,z,axis,true,false))normalAxes|=1<<axis;
            if((normalAxes&1<<axis)!=0 || smart&&cross(v,x,y,z,axis,true,true))axes|=1<<axis;
        }
        tested[index]=(byte)(8|axes|(normalAxes<<4));
        if(axes!=0) follow.add(index);
    }
    private static boolean room(Volume v,int x,int y,int z,int axis) {
        int air=0;
        for(int a=-2;a<=2;a++)for(int b=-2;b<=2;b++)if(at(v,x,y,z,axis,a,b)==AIR)air++;
        return air>=23;
    }
    private static int plug(Volume v,int x,int y,int z,int dir) {
        int axis=dir/2;
        // Two aligned sections behind the blockage establish the recent heading.
        if (!cross(v,x-DX[dir],y-DY[dir],z-DZ[dir],axis,true,true)) return 0;
        for(int step=1;step<=PLUG_LOOK_AHEAD+1;step++) {
            int nx=x+DX[dir]*step,ny=y+DY[dir]*step,nz=z+DZ[dir]*step;
            if(cross(v,nx,ny,nz,axis,true,true)) {
                return step>1 && cross(v,nx+DX[dir],ny+DY[dir],nz+DZ[dir],axis,true,true) ? step : 0;
            }
            if(step>PLUG_LOOK_AHEAD || !slab(v,nx,ny,nz,axis)) return 0;
        }
        return 0;
    }
    private static boolean slab(Volume v,int x,int y,int z,int axis) {
        int mask=0;
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
            int block=at(v,x,y,z,axis,a,b);
            if(block==SOLID)mask|=1<<((a+1)*3+b+1);
            else if(block!=AIR)return false;
        }
        // One or more contiguous complete rows/columns, with no scattered cells.
        for(int start=0;start<3;start++)for(int end=start;end<3;end++) {
            int rows=0,cols=0;
            for(int i=start;i<=end;i++){rows|=7<<(i*3);cols|=73<<i;}
            if(mask==rows||mask==cols)return true;
        }
        return false;
    }
    private static int at(Volume v, int x, int y, int z, int axis, int a, int b) {
        return switch (axis) {
            case 0 -> v.at(x, y + a, z + b);
            case 1 -> v.at(x + a, y, z + b);
            default -> v.at(x + a, y + b, z);
        };
    }
    private static boolean solid(Volume v,int x,int y,int z,int axis,int a,int b) {
        if(axis==0){y+=a;z+=b;}else if(axis==1){x+=a;z+=b;}else{x+=a;y+=b;}
        int bit=x-v.x;
        return bit>=0&&bit<v.width&&((v.row(v.solidRows,y,z)>>>bit)&1)!=0;
    }
    private static boolean unknownCross(Volume v, int x, int y, int z, int axis) {
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++)
            if (at(v, x, y, z, axis, a, b) == UNKNOWN) return true;
        return false;
    }
    private static boolean cross(Volume v,int x,int y,int z,int axis,boolean walls,boolean smart) {
        int lx=x-v.x;
        if(lx<1||lx>=v.width-1||y<=v.y||y>=v.y+v.height-1||z<=v.z||z>=v.z+v.depth-1)return false;
        long bits;
        if(axis==0) {
            bits=-1L;
            for(int dy=-1;dy<=1;dy++) bits &= v.row(v.airRows,y+dy,z-1)&v.row(v.airRows,y+dy,z)&v.row(v.airRows,y+dy,z+1);
            bits=(bits>>>lx)&1;
        } else {
            bits=axis==1 ? v.row(v.airRows,y,z-1)&v.row(v.airRows,y,z)&v.row(v.airRows,y,z+1)
                    : v.row(v.airRows,y-1,z)&v.row(v.airRows,y,z)&v.row(v.airRows,y+1,z);
            bits=(bits>>>(lx-1))&7;
        }
        boolean clear=bits==(axis==0?1:7);
        if(!clear) {
            if(!smart)return false;
            int imperfect=0;
            for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
                int block=at(v,x,y,z,axis,a,b);
                if(block==UNKNOWN || block!=AIR && ++imperfect>TOLERATED_BLOCKS)return false;
            }
        }
        if(!walls)return true;
        int support=wallSupport(v,x,y,z,axis);
        if(support>=MIN_WALL_BLOCKS)return true;
        if(!smart||support<SMART_JOINT_WALL_BLOCKS)return false;
        int dir=axis*2;
        // A continuing slice, or two orientations overlapping at the same corner,
        // establishes a joint. A slice across an end cap cannot establish one alone.
        if(joint(v,x,y,z,axis))return true;
        if(!cross(v,x+DX[dir],y+DY[dir],z+DZ[dir],axis,false,true)
                || !cross(v,x-DX[dir],y-DY[dir],z-DZ[dir],axis,false,true))return false;
        for(int step=-GRID_SPACING;step<=GRID_SPACING;step++) {
            if(step==0)continue;
            int nx=x+DX[dir]*step,ny=y+DY[dir]*step,nz=z+DZ[dir]*step;
            if(cross(v,nx,ny,nz,axis,false,true) && wallSupport(v,nx,ny,nz,axis)>=SMART_JOINT_WALL_BLOCKS
                    && joint(v,nx,ny,nz,axis))return true;
        }
        return false;
    }
    private static boolean joint(Volume v,int x,int y,int z,int axis) {
        for(int other=0;other<3;other++)
            if(other!=axis && cross(v,x,y,z,other,false,true)
                    && wallSupport(v,x,y,z,other)>=SMART_JOINT_WALL_BLOCKS)return true;
        return false;
    }
    private static int wallSupport(Volume v,int x,int y,int z,int axis) {
        int solid=0;
        for(int a=-1;a<=1;a++) {
            if(solid(v,x,y,z,axis,-2,a))solid++;
            if(solid(v,x,y,z,axis,2,a))solid++;
            if(solid(v,x,y,z,axis,a,-2))solid++;
            if(solid(v,x,y,z,axis,a,2))solid++;
        }
        return solid;
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
