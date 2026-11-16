package com.scrimmy.client.basefinding;

import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.trail.TunnelAnalysis;
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

/** Only load/change events dirty snapshots. World reads stay on ticks under a fixed deadline. */
public final class DrillTrailScanner implements AutoCloseable {
    public static final long SNAPSHOT_BUDGET_NANOS = 1_000_000;
    public static final int HALO = MAX_GAP + 2, COPY_SLICE = 256, DISCOVER_PER_TICK = 8;
    private final DrillTrail module;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Scrimmy DrillTrail scan"); thread.setDaemon(true); return thread;
    });
    private final Map<BlockState, Byte> kinds = new IdentityHashMap<>();
    private final LongOpenHashSet known = new LongOpenHashSet();
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
    private ClientLevel world;
    private Snapshot copying, submitted;
    private Future<TunnelAnalysis.Chunk> task;
    private long revision;
    private int centerX = Integer.MIN_VALUE, centerZ, range, discovery, walkX, walkZ, dx, dz = -1;

    public DrillTrailScanner(DrillTrail module) { this.module = module; }
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
        known.add(chunk.getPos().toLong()); changed(level, x, z); dirty.add(chunk.getPos().toLong());
    }
    public void changed(ClientLevel level, int x, int z) {
        if (world != level || !module.enabled()) return;
        if (centerX != Integer.MIN_VALUE && (Math.abs(x - centerX) > range + 1 || Math.abs(z - centerZ) > range + 1)) return;
        revisions.put(ChunkPos.asLong(x, z), ++revision);
        // Cross-sections, intersection rays and hazard hints all read the neighboring halo.
        for (int oz = -1; oz <= 1; oz++) for (int ox = -1; ox <= 1; ox++) {
            long key = ChunkPos.asLong(x + ox, z + oz); if (known.contains(key)) dirty.add(key);
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
        long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
        if (task != null && task.isDone()) {
            try { if (submitted.valid()) module.accept(task.get()); }
            catch (CancellationException ignored) { }
            catch (Exception error) { module.error("Tunnel scan failed", error); }
            task = null; submitted = null;
        }
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        int r = Math.min(mc.options.renderDistance().get() + 1, (int)Math.ceil(module.range.get() / 16));
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
        while (System.nanoTime() < deadline) {
            if (copying == null) {
                if (dirty.isEmpty()) return;
                var it = dirty.iterator(); long key = it.next(); it.remove();
                int x = (int)key, z = (int)(key >>> 32);
                LevelChunk target = chunk(x, z);
                if (target == null || Math.abs(x - cx) > r || Math.abs(z - cz) > r) continue;
                copying = new Snapshot(x, z, target);
            }
            if (!copying.valid()) { copying = null; continue; }
            if (!copying.copySlice()) continue;
            Snapshot ready = copying; copying = null;
            if (!ready.hasCandidates) { module.accept(new TunnelAnalysis.Chunk(ChunkPos.asLong(ready.x, ready.z), List.of())); continue; }
            submitted = ready;
            TunnelAnalysis.Volume volume = ready.volume();
            task = worker.submit(() -> TunnelAnalysis.analyze(volume));
            return;
        }
    }
    private final class Snapshot {
        final int x, z, minY, height, width = 16 + 2 * HALO;
        final LevelChunk[] chunks = new LevelChunk[9];
        final long[] versions = new long[9];
        final boolean[] candidates, copySections;
        byte[] blocks;
        int filter, section, cursor;
        boolean hasCandidates;
        Snapshot(int x, int z, LevelChunk target) {
            this.x = x; this.z = z; minY = world.getMinY(); height = world.getMaxY() + 1 - minY;
            candidates = new boolean[target.getSections().length]; copySections = new boolean[candidates.length];
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
            }
            if (section >= candidates.length) return true;
            if (!copySections[section]) { section++; return false; }
            int end = Math.min(width * width * 16, cursor + COPY_SLICE);
            while (cursor < end) {
                int index = cursor++, bx = index % width, bz = index / width % width, ly = index / (width * width);
                int px = bx + 16 - HALO, pz = bz + 16 - HALO;
                LevelChunk source = chunks[(pz >> 4) * 3 + (px >> 4)];
                if (source == null) continue;
                LevelChunkSection states = source.getSections()[section];
                blocks[((section * 16 + ly) * width + bz) * width + bx] = states.hasOnlyAir() ? AIR
                        : kinds.getOrDefault(states.getBlockState(px & 15, ly, pz & 15), SOLID);
            }
            if (cursor == width * width * 16) { cursor = 0; section++; }
            return false;
        }
        TunnelAnalysis.Volume volume() {
            return new TunnelAnalysis.Volume(x * 16 - HALO, minY, z * 16 - HALO, width, height, width,
                    x, z, blocks, candidates);
        }
    }
    @Override public void close() { reset(null); worker.shutdownNow(); }
}
