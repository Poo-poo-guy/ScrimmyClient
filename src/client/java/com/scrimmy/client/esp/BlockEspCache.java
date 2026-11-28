package com.scrimmy.client.esp;

import com.scrimmy.client.module.visual.BlockESPModule;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/** Client-thread knowledge survives drawing limits; scans and nearest selection resume across ticks. */
public final class BlockEspCache {
    public static final int MAX_RENDERED = 2048;
    private final BlockESPModule module;
    private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    private final ArrayList<Chunk> chunkList = new ArrayList<>();
    private final PriorityQueue<Chunk> pending = new PriorityQueue<>(Comparator.comparingDouble(c -> chunkDistance(c.x, c.z)));
    private final Predicate<BlockState> matches = state -> moduleMatches(state.getBlock());
    private final Candidate[] nearest = new Candidate[MAX_RENDERED];
    private final Visual[] visuals = new Visual[MAX_RENDERED * 2], free = new Visual[MAX_RENDERED * 2];
    private final Long2ObjectOpenHashMap<Visual> visible = new Long2ObjectOpenHashMap<>();
    private final Comparator<Candidate> nearestOrder = Comparator.comparingDouble(e -> e.distance);
    private ClientLevel world;
    private int centerX = Integer.MIN_VALUE, centerZ, radius, discovery, selectChunk, selectSection, selectLocal, nearestCount, total, visualCount, freeCount, selectionCap;
    private int[][] offsets = new int[0][];
    private double playerX, playerY, playerZ, selectionX, selectionY, selectionZ;
    private boolean selecting;
    private long renderRevision;
    private final long epoch = System.nanoTime();
    public float seconds() { return (System.nanoTime() - epoch) / 1_000_000_000f; }
    public long renderRevision() { return renderRevision; }

    public BlockEspCache(BlockESPModule module) {
        this.module = module;
        for (int i = 0; i < free.length; i++) free[freeCount++] = new Visual();
        for (int i = 0; i < nearest.length; i++) nearest[i] = new Candidate();
    }
    private boolean moduleMatches(Block block) { return module.matches(block); }
    private double chunkDistance(int x, int z) {
        double dx = x * 16 + 8 - playerX, dz = z * 16 + 8 - playerZ;
        return dx * dx + dz * dz;
    }
    public ClientLevel world() { return world; }
    public int size() { return visualCount; }
    public Visual visual(int index) { return visuals[index]; }
    public int cachedCount() { return total; }
    public void clear() {
        rebuild(); visible.clear();
        for (int i = 0; i < visualCount; i++) {
            Visual v = visuals[i]; v.entry.reset(); free[freeCount++] = v; visuals[i] = null;
        }
        visualCount = 0; world = null; renderRevision++;
    }
    /** Invalidate entire chunks in constant time per chunk; retain only bounded fading visuals. */
    public void rebuild() {
        for (Chunk chunk : chunkList) chunk.validity.active = false;
        chunks.clear(); chunkList.clear(); pending.clear(); total = 0;
        resetSelection();
        for (int i = 0; i < visualCount; i++) transition(visuals[i], false, seconds());
        centerX = Integer.MIN_VALUE; discovery = 0;
    }
    private void resetSelection() {
        nearestCount = 0; selecting = false; selectChunk = selectSection = selectLocal = 0;
        for (Candidate candidate : nearest) candidate.validity = null;
    }
    public void load(ClientLevel level, LevelChunk levelChunk) {
        if (world != level) { clear(); world = level; }
        int x = levelChunk.getPos().x, z = levelChunk.getPos().z;
        Chunk old = chunks.get(ChunkPos.asLong(x, z));
        if (old != null) discard(old);
        Chunk chunk = new Chunk(levelChunk, playerY);
        chunks.put(ChunkPos.asLong(x, z), chunk); chunkList.add(chunk); pending.add(chunk);
    }
    public void unload(ClientLevel level, LevelChunk chunk) {
        if (world != level) return;
        Chunk old = chunks.get(chunk.getPos().toLong());
        if (old != null && old.chunk == chunk) discard(old);
    }
    private void discard(Chunk chunk) {
        chunks.remove(ChunkPos.asLong(chunk.x, chunk.z)); pending.remove(chunk); chunkList.remove(chunk);
        chunk.validity.active = false; total -= chunk.count;
        float now = seconds();
        for (int i = 0; i < visualCount; i++)
            if (visuals[i].entry.validity == chunk.validity) transition(visuals[i], false, now);
        // Removing a chunk can shift a resumable cursor.
        selecting = false;
    }
    public void changed(ClientLevel level, BlockPos pos, BlockState state) {
        if (world != level) return;
        Chunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) return;
        int section = (pos.getY() - chunk.minY) >> 4;
        if (section < 0 || section >= chunk.bits.length) return;
        int local = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        boolean match = module.matches(state.getBlock());
        long[] bits = chunk.bits[section];
        boolean previous = bits != null && (bits[local >> 6] & (1L << (local & 63))) != 0;
        if (!match && !previous) return;
        setMatch(chunk, section, local, match ? state.getBlock() : null);
        Visual visual = visible.get(pos.asLong());
        if (visual != null && visual.entry.block != state.getBlock()) { visual.entry.alive = false; transition(visual, false, seconds()); }
        // Don't publish a candidate selected before a server/prediction update.
        selecting = false;
    }
    private void setMatch(Chunk chunk, int section, int local, Block block) {
        boolean match = block != null;
        long pos = BlockPos.asLong(chunk.x * 16 + (local & 15), chunk.minY + section * 16 + (local >> 8), chunk.z * 16 + ((local >> 4) & 15));
        if (match) chunk.blocks.put(pos, block); else chunk.blocks.remove(pos);
        long[] bits = chunk.bits[section];
        if (bits == null) {
            if (!match) return;
            chunk.bits[section] = bits = new long[64];
        }
        long mask = 1L << (local & 63);
        int word = local >> 6;
        boolean previous = (bits[word] & mask) != 0;
        if (previous == match) return;
        if (match) { bits[word] |= mask; chunk.count++; total++; }
        else { bits[word] &= ~mask; chunk.count--; total--; }
    }
    public void tick(Minecraft mc) {
        if (world != mc.level) { clear(); world = mc.level; }
        if (world == null || mc.player == null || mc.isPaused()) return;
        float now = seconds();
        for (int i = visualCount - 1; i >= 0; i--) {
            Visual visual = visuals[i];
            if (!visual.entry.alive()) transition(visual, false, now);
            if (!visual.wanted && visual.opacity(now) < .002) removeVisual(i);
        }
        long start = System.nanoTime(), budget = (long)(BlockESPModule.SCAN_SPEED * 1_000_000);
        playerX = mc.player.getX(); playerY = mc.player.getY(); playerZ = mc.player.getZ();
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        // ClientChunkCache keeps renderDistance + 3 chunks, even when BlockESP draws less.
        int r = Math.max(2, mc.options.renderDistance().get()) + 3;
        if (cx != centerX || cz != centerZ || r != radius) recenter(cx, cz, r);
        long discoveryEnd = start + budget / 5;
        while (discovery < offsets.length && System.nanoTime() < discoveryEnd) {
            int[] offset = offsets[discovery++];
            int x = centerX + offset[0], z = centerZ + offset[1];
            if (!chunks.containsKey(ChunkPos.asLong(x, z))) {
                LevelChunk chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) load(world, chunk);
            }
        }
        long scanEnd = start + budget * 3 / 5;
        while (!pending.isEmpty() && System.nanoTime() < scanEnd) {
            Chunk chunk = pending.peek();
            if (scan(chunk)) pending.poll();
        }
        // Nearest selection also resumes under the deadline; no full-cache sort on a tick/frame.
        if (!selecting) {
            resetSelection(); selecting = true; selectionCap = module.maxBlocks.get().intValue();
            selectionX = playerX; selectionY = playerY; selectionZ = playerZ;
        }
        long end = start + budget;
        int steps = 0;
        while (System.nanoTime() < end && steps++ < 8192) {
            if (selectChunk >= chunkList.size()) { publish(); selecting = false; break; }
            Chunk chunk = chunkList.get(selectChunk);
            if (selectSection >= chunk.sectionOrder.length) { nextChunk(); continue; }
            int section = chunk.sectionOrder[selectSection];
            int baseY = chunk.minY + section * 16;
            double limit = nearestCount == selectionCap ? Math.min(nearest[0].distance, module.range.get() * module.range.get()) : module.range.get() * module.range.get();
            // Once the heap fills, entire distant sections are cheaper to reject than their bits.
            if (sectionDistance(chunk, baseY) > limit || chunk.bits[section] == null) { nextSection(); continue; }
            int local = nextSetBit(chunk.bits[section], selectLocal);
            if (local < 0) { nextSection(); continue; }
            selectLocal = local + 1;
            int x = chunk.x * 16 + (local & 15), y = baseY + (local >> 8), z = chunk.z * 16 + ((local >> 4) & 15);
            double dx = x + .5 - selectionX, dy = y + .5 - selectionY, dz = z + .5 - selectionZ;
            double distance = dx*dx + dy*dy + dz*dz;
            if (distance > limit) continue;
            long pos = BlockPos.asLong(x, y, z);
            Block block = chunk.blocks.get(pos);
            if (block != null) offer(pos, block, chunk.validity, distance);
        }
    }
    private double sectionDistance(Chunk chunk, int y) {
        double dx = Math.max(0, Math.max(chunk.x*16 - selectionX, selectionX - (chunk.x*16+16)));
        double dy = Math.max(0, Math.max(y - selectionY, selectionY - (y+16)));
        double dz = Math.max(0, Math.max(chunk.z*16 - selectionZ, selectionZ - (chunk.z*16+16)));
        return dx*dx + dy*dy + dz*dz;
    }
    static int nextSetBit(long[] bits, int from) {
        if (from >= 4096) return -1;
        int word = from >> 6;
        long remaining = bits[word] & (-1L << (from & 63));
        while (remaining == 0) {
            if (++word == bits.length) return -1;
            remaining = bits[word];
        }
        return word * 64 + Long.numberOfTrailingZeros(remaining);
    }
    private void nextChunk() { selectChunk++; selectSection = selectLocal = 0; }
    private void nextSection() { selectSection++; selectLocal = 0; }
    private void recenter(int x, int z, int r) {
        boolean resized = radius != r || offsets.length == 0;
        centerX = x; centerZ = z; radius = r; discovery = 0;
        if (resized) {
            offsets = new int[(r*2+1)*(r*2+1)][2];
            int i = 0;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) { offsets[i][0] = dx; offsets[i++][1] = dz; }
            Arrays.sort(offsets, Comparator.comparingInt(a -> a[0]*a[0] + a[1]*a[1]));
        }
        // Moving or changing draw range never evicts client-loaded knowledge.
        selecting = false;
    }
    /** At most 64 lookups per slice; empty/nonmatching palettes skip entire sections. */
    private boolean scan(Chunk chunk) {
        if (chunk.section >= chunk.sectionOrder.length) return true;
        int sectionIndex = chunk.sectionOrder[chunk.section];
        LevelChunkSection section = chunk.chunk.getSections()[sectionIndex];
        if (chunk.cursor == 0 && (section.hasOnlyAir() || !section.maybeHas(matches))) { chunk.section++; return chunk.section == chunk.sectionOrder.length; }
        int max = Math.min(4096, chunk.cursor + 64);
        while (chunk.cursor < max) {
            int local = chunk.cursor++;
            Block block = section.getBlockState(local & 15, local >> 8, (local >> 4) & 15).getBlock();
            setMatch(chunk, sectionIndex, local, module.matches(block) ? block : null);
        }
        if (chunk.cursor == 4096) { chunk.cursor = 0; chunk.section++; }
        return chunk.section == chunk.sectionOrder.length;
    }
    private void offer(long pos, Block block, Validity validity, double distance) {
        Candidate candidate;
        int i;
        if (nearestCount < selectionCap) {
            i = nearestCount++; candidate = nearest[i];
            while (i > 0) {
                int parent = (i-1)/2;
                if (nearest[parent].distance >= distance) break;
                nearest[i] = nearest[parent]; i = parent;
            }
        } else {
            if (distance >= nearest[0].distance) return;
            candidate = nearest[0]; i = 0;
            while (i*2+1 < nearestCount) {
                int child = i*2+1;
                if (child+1 < nearestCount && nearest[child+1].distance > nearest[child].distance) child++;
                if (nearest[child].distance <= distance) break;
                nearest[i] = nearest[child]; i = child;
            }
        }
        candidate.pos = pos; candidate.block = block; candidate.validity = validity; candidate.distance = distance;
        nearest[i] = candidate;
    }
    private void publish() {
        for (int i = 0; i < visualCount; i++) visuals[i].selected = false;
        float now = seconds();
        Arrays.sort(nearest, 0, nearestCount, nearestOrder);
        for (int i = 0; i < Math.min(nearestCount, module.maxBlocks.get().intValue()); i++) {
            Candidate candidate = nearest[i];
            if (!candidate.validity.active) continue;
            Visual visual = visible.get(candidate.pos);
            // A replacement at the same position finishes the old block's fade before re-entering.
            if (visual != null && (!visual.entry.alive() || visual.entry.block != candidate.block) && visual.opacity(now) >= .002) continue;
            if (visual == null) {
                if (freeCount == 0) continue;
                visual = free[--freeCount]; free[freeCount] = null;
                visual.alpha = 0; visual.wanted = false; visual.changedAt = now; renderRevision++; visuals[visualCount++] = visual; visible.put(candidate.pos, visual);
            }
            visual.entry.pos = candidate.pos; visual.entry.block = candidate.block;
            visual.entry.validity = candidate.validity; visual.entry.alive = true; visual.selected = true;
            transition(visual, true, now);
        }
        for (int i = 0; i < visualCount; i++) transition(visuals[i], visuals[i].selected && visuals[i].entry.alive(), now);
    }
    private void transition(Visual visual, boolean wanted, float now) {
        if (visual.wanted == wanted) return;
        visual.alpha = visual.opacity(now); visual.changedAt = now; visual.wanted = wanted;
        renderRevision++;
    }
    public void removeVisual(int index) {
        Visual visual = visuals[index]; visible.remove(visual.entry.pos);
        visuals[index] = visuals[--visualCount]; visuals[visualCount] = null;
        visual.entry.reset(); visual.wanted = false; free[freeCount++] = visual; renderRevision++;
    }
    public static final class Visual {
        public final Entry entry = new Entry();
        public boolean wanted, selected;
        public double alpha;
        public float changedAt;
        public double opacity(float now) {
            double target = wanted ? 1 : 0;
            return target + (alpha - target) * Math.exp(-Math.max(0, now - changedAt) * 4 / BlockESPModule.FADE_TIME);
        }
    }
    public static final class Entry {
        public long pos;
        public Block block;
        private boolean alive;
        private Validity validity;
        public boolean alive() { return alive && validity != null && validity.active; }
        private void reset() { block = null; validity = null; alive = false; }
    }
    private static final class Candidate {
        long pos; Block block; Validity validity; double distance;
    }
    private static final class Validity { boolean active = true; }
    private static final class Chunk {
        final Validity validity = new Validity();
        final LevelChunk chunk;
        final int x, z, minY;
        final int[] sectionOrder;
        final long[][] bits;
        final Long2ObjectOpenHashMap<Block> blocks = new Long2ObjectOpenHashMap<>();
        int section, cursor, count;
        Chunk(LevelChunk chunk, double playerY) {
            this(chunk, chunk.getPos().x, chunk.getPos().z, chunk.getMinY(), chunk.getSections().length, playerY);
        }
        Chunk(LevelChunk chunk, int x, int z, int minY, int sectionCount, double playerY) {
            this.chunk = chunk; this.x = x; this.z = z; this.minY = minY;
            Integer[] order = new Integer[sectionCount];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingDouble(i -> Math.abs(minY+i*16+8-playerY)));
            sectionOrder = new int[order.length]; bits = new long[order.length][];
            for (int i = 0; i < order.length; i++) sectionOrder[i] = order[i];
        }
    }
}
