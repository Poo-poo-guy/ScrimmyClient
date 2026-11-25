package com.scrimmy.client.basefinding;

import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.trail.TunnelAnalysis;
import com.scrimmy.client.trail.TrailTimings;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;
import static com.scrimmy.client.trail.TunnelAnalysis.*;

/** Load/change events dirty snapshots; live reads stay on ticks under an adaptive deadline. */
public final class DrillTrailScanner implements AutoCloseable {
    public static final long MIN_BUDGET_NANOS = 200_000, MAX_BUDGET_NANOS = 2_000_000;
    public static final int HALO = MAX_GAP + 2, COPY_SLICE = 1024, DISCOVER_PER_TICK = 8;
    public interface Consumer {
        boolean enabled();
        double rangeBlocks();
        boolean smart();
        void accept(TunnelAnalysis.Chunk chunk);
        void error(String message, Throwable error);
    }
    private final Consumer module;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Scrimmy DrillTrail scan"); thread.setDaemon(true); return thread;
    });
    private final Map<BlockState, Byte> kinds = new IdentityHashMap<>();
    private final java.util.function.Predicate<BlockState> nonSolid=state->kinds.getOrDefault(state,SOLID)!=SOLID;
    private final LongOpenHashSet known = new LongOpenHashSet();
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap dirty = new Long2IntOpenHashMap();
    private long budget = 1_000_000, lastTick;
    private ClientLevel world;
    private Snapshot copying, submitted;
    private Future<TunnelAnalysis.Chunk> task;
    private long revision;
    private int centerX = Integer.MIN_VALUE, centerZ, range, discovery, walkX, walkZ, dx, dz = -1;

    public DrillTrailScanner(DrillTrail module) {
        this(new Consumer() {
            public boolean enabled() { return module.enabled(); }
            public double rangeBlocks() { return module.range.get(); }
            public boolean smart() { return module.smart(); }
            public void accept(TunnelAnalysis.Chunk chunk) { module.accept(chunk); }
            public void error(String message, Throwable error) { module.error(message, error); }
        });
    }
    public DrillTrailScanner(Consumer module) { this.module = module; }
    public void initialize() {
        for (Block block : BuiltInRegistries.BLOCK) for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            byte kind = state.isAir() ? AIR : state.is(Blocks.LAVA) ? LAVA : state.is(Blocks.WATER) ? WATER
                    : state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB)
                    || state.is(Blocks.SWEET_BERRY_BUSH) ? DANGER : SOLID;
            kinds.put(state, kind);
        }
    }
    public void reset(ClientLevel level) {
        if (task != null) task.cancel(true);
        task = null; copying = submitted = null; world = level;
        known.clear(); dirty.clear(); revisions.clear(); centerX = Integer.MIN_VALUE;
    }
    private LevelChunk chunk(int x, int z) { return world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false); }
    public void load(ClientLevel level, LevelChunk chunk) {
        if (world != level || !module.enabled()) return;
        int x = chunk.getPos().x, z = chunk.getPos().z;
        if (centerX != Integer.MIN_VALUE && (Math.abs(x - centerX) > range || Math.abs(z - centerZ) > range)) return;
        known.add(chunk.getPos().toLong()); changed(level, x, z); dirty.put(chunk.getPos().toLong(), 0);
    }
    public void changed(ClientLevel level, int x, int z) {
        if (world != level || !module.enabled()) return;
        if (centerX != Integer.MIN_VALUE && (Math.abs(x - centerX) > range + 1 || Math.abs(z - centerZ) > range + 1)) return;
        revisions.put(ChunkPos.asLong(x, z), ++revision);
        long target=ChunkPos.asLong(x,z);
        if(known.contains(target))dirty.put(target,0);
        // Recheck only the strips that can read this neighbor's halo. Existing interior
        // evidence stays cached. Full invalidations always dominate strip invalidations.
        for(int oz=-1;oz<=1;oz++)for(int ox=-1;ox<=1;ox++) {
            if(ox==0&&oz==0)continue;
            long key=ChunkPos.asLong(x+ox,z+oz);
            if(!known.contains(key))continue;
            int edges=(ox<0?2:ox>0?1:0)|(oz<0?8:oz>0?4:0);
            if(!dirty.containsKey(key))dirty.put(key,edges);
            else if(dirty.get(key)!=0)dirty.put(key,dirty.get(key)|edges);
        }
    }
    public void unload(ClientLevel level, LevelChunk chunk) {
        if (world != level) return;
        long key = chunk.getPos().toLong(); known.remove(key); dirty.remove(key);
        changed(level, chunk.getPos().x, chunk.getPos().z); revisions.remove(key);
        // The module's session history deliberately survives this event.
    }
    public void tick(Minecraft mc) {
        if (world != mc.level) reset(mc.level);
        if (world == null || mc.player == null || mc.isPaused() || !module.enabled()) return;
        long now=System.nanoTime(), interval=lastTick==0?50_000_000:now-lastTick; lastTick=now;
        budget=Math.max(MIN_BUDGET_NANOS,Math.min(MAX_BUDGET_NANOS,
                interval>55_000_000 ? budget*3/4 : budget+25_000));
        long deadline=now+budget;
        if (task != null && task.isDone()) {
            try { if (submitted.valid()) module.accept(task.get()); }
            catch (CancellationException ignored) { }
            catch (Exception error) { module.error("Tunnel scan failed", error); }
            task = null; submitted = null;
        }
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        int r = Math.min(mc.options.renderDistance().get() + 1, (int)Math.ceil(module.rangeBlocks() / 16));
        if (cx != centerX || cz != centerZ || r != range) {
            centerX = cx; centerZ = cz; range = r; discovery = walkX = walkZ = dx = 0; dz = -1;
            var keys = known.iterator();
            while (keys.hasNext()) {
                long key = keys.nextLong();
                if (Math.abs((int)key - cx) <= r && Math.abs((int)(key >>> 32) - cz) <= r) continue;
                keys.remove(); dirty.remove(key); revisions.remove(key);
            }
            var versions = revisions.keySet().iterator();
            while (versions.hasNext()) {
                long key = versions.nextLong();
                if (Math.abs((int)key - cx) > r + 1 || Math.abs((int)(key >>> 32) - cz) > r + 1) versions.remove();
            }
        }
        int side = 2 * r + 1;
        for (int i = 0; i < DISCOVER_PER_TICK && discovery < side * side && System.nanoTime() < deadline; i++, discovery++) {
            int x = cx + walkX, z = cz + walkZ;
            if (walkX == walkZ || walkX < 0 && walkX == -walkZ || walkX > 0 && walkX == 1 - walkZ) {
                int old = dx; dx = -dz; dz = old;
            }
            walkX += dx; walkZ += dz;
            LevelChunk found = chunk(x, z);
            if (found != null && !known.contains(found.getPos().toLong())) load(world, found);
        }
        if (task != null) return;
        if(copying!=null&&!copying.valid())copying=null;
        while (System.nanoTime() < deadline) {
            if (copying == null) {
                if (dirty.isEmpty()) return;
                long key=nextChunk(mc,cx,cz);
                int edges=dirty.remove(key);
                int x = (int)key, z = (int)(key >>> 32);
                LevelChunk target = chunk(x, z);
                if (target == null || Math.abs(x - cx) > r || Math.abs(z - cz) > r) continue;
                long timing=TrailTimings.start();
                copying = new Snapshot(x, z, target, edges);
                TrailTimings.end(TrailTimings.SNAPSHOT,timing);
            }
            long timing=TrailTimings.start();
            boolean complete=copying.copySlice();
            TrailTimings.end(TrailTimings.SNAPSHOT,timing);
            if (!complete) continue;
            Snapshot ready = copying; copying = null;
            if (!ready.hasCandidates) { module.accept(new TunnelAnalysis.Chunk(ChunkPos.asLong(ready.x, ready.z), List.of(), ready.edges)); continue; }
            submitted = ready;
            TunnelAnalysis.Volume volume = ready.volume();
            boolean smart=module.smart();
            task = worker.submit(() -> TunnelAnalysis.analyze(volume,smart));
            return;
        }
    }
    private long nextChunk(Minecraft mc,int cx,int cz) {
        long best=0; double score=Double.MAX_VALUE;
        var motion=mc.player.getDeltaMovement();
        double speed=Math.hypot(motion.x,motion.z),mx=speed>.01?motion.x/speed:0,mz=speed>.01?motion.z/speed:0;
        for(long key:dirty.keySet()) {
            int dx=(int)key-cx,dz=(int)(key>>>32)-cz;
            double priority=dx*dx+dz*dz-2*(dx*mx+dz*mz);
            if(priority<score){score=priority;best=key;}
        }
        return best;
    }
    private final class Snapshot {
        final int x, z, minY, height, edges, width = 16 + 2 * HALO;
        final LevelChunk[] chunks = new LevelChunk[9];
        final long[] versions = new long[9];
        final boolean[] candidates, copySections;
        final byte[][] uniform;
        byte[] blocks;
        long[] airRows,solidRows;
        int filter, section, cursor;
        int classified=-1;
        boolean hasCandidates;
        Snapshot(int x, int z, LevelChunk target, int edges) {
            this.edges=edges;
            this.x = x; this.z = z; minY = world.getMinY(); height = world.getMaxY() + 1 - minY;
            candidates = new boolean[target.getSections().length]; copySections = new boolean[candidates.length];
            uniform=new byte[9][candidates.length];
            for (int i = 0; i < 9; i++) {
                int nx = x + i % 3 - 1, nz = z + i / 3 - 1;
                chunks[i] = chunk(nx, nz); versions[i] = revisions.get(ChunkPos.asLong(nx, nz));
            }
        }
        boolean valid() {
            for (int i = 0; i < 9; i++) {
                int nx = x + i % 3 - 1, nz = z + i / 3 - 1;
                if (chunk(nx, nz) != chunks[i] || revisions.get(ChunkPos.asLong(nx, nz)) != versions[i]) return false;
            }
            return true;
        }
        boolean copySlice() {
            if (blocks == null) {
                if (filter < candidates.length) {
                    int index = filter++; LevelChunkSection states = chunks[4].getSections()[index];
                    // A completely open section cannot have two opposing flat walls only 3 blocks apart.
                    if (!states.hasOnlyAir() && states.maybeHas(BlockState::isAir)) {
                        candidates[index] = hasCandidates = true;
                        for (int i = Math.max(0, index - 1); i <= Math.min(candidates.length - 1, index + 1); i++) copySections[i] = true;
                    }
                    return false;
                }
                if (!hasCandidates) return true;
                blocks = new byte[width * width * height];
                airRows=new long[width*height];solidRows=new long[width*height];
            }
            if (section >= candidates.length) return true;
            if (!copySections[section]) { section++; return false; }
            if(classified!=section) {
                for(int i=0;i<chunks.length;i++) {
                    if(chunks[i]==null)continue;
                    LevelChunkSection states=chunks[i].getSections()[section];
                    uniform[i][section]=states.hasOnlyAir()?AIR:!states.maybeHas(nonSolid)?SOLID:UNKNOWN;
                }
                classified=section;
            }
            int end = Math.min(width * width * 16, cursor + COPY_SLICE);
            while (cursor < end) {
                int index = cursor++, bx = index % width, bz = index / width % width, ly = index / (width * width);
                int px = bx + 16 - HALO, pz = bz + 16 - HALO;
                int sourceIndex=(pz>>4)*3+(px>>4);
                LevelChunk source = chunks[sourceIndex];
                if (source == null) continue;
                LevelChunkSection states = source.getSections()[section];
                byte kind=uniform[sourceIndex][section];
                if(kind==UNKNOWN)kind=kinds.getOrDefault(states.getBlockState(px&15,ly,pz&15),SOLID);
                int row=(section*16+ly)*width+bz;
                blocks[row*width+bx]=kind;
                if(kind==AIR)airRows[row]|=1L<<bx;
                if(kind==SOLID)solidRows[row]|=1L<<bx;
            }
            if (cursor == width * width * 16) { cursor = 0; section++; }
            return false;
        }
        TunnelAnalysis.Volume volume() {
            return new TunnelAnalysis.Volume(x * 16 - HALO, minY, z * 16 - HALO, width, height, width,
                    x, z, blocks, candidates, airRows, solidRows, edges);
        }
    }
    @Override public void close() { reset(null); worker.shutdownNow(); }
}
