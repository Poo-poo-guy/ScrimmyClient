package com.scrimmy.client.basefinding;

import static com.scrimmy.client.basefinding.ActivityTerrainAnalysis.*;

import com.scrimmy.client.module.basefinding.ChunkFinderV2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;

/** One 0.75ms tick budget for loaded-only discovery and 128-block snapshot slices. */
public final class ActivityTerrainScanner implements AutoCloseable {
    private final ChunkFinderV2 module;
    private final Map<BlockState, Short> kinds = new IdentityHashMap<>();
    private final Map<Long, LevelChunk> known = new HashMap<>();
    private final Map<Long, Long> revisions = new HashMap<>();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Scrimmy V2 terrain"); t.setDaemon(true); return t;
    });
    private ClientLevel world;
    private Snapshot copying, submitted;
    private Future<HumanActivityAnalysis.Terrain> task;
    private long revision;
    private int cx = Integer.MIN_VALUE, cz, radius, discovery, walkX, walkZ, dx, dz = -1;
    public ActivityTerrainScanner(ChunkFinderV2 module) { this.module = module; }
    public void initialize() {
        for (var block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            int rarity = HumanActivityAnalysis.rarity(id);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int kind = id.equals("water") ? WATER : id.equals("lava") ? LAVA
                        : state.isAir() || rarity == 0 && !state.canOcclude() ? OPEN
                        : id.equals("dirt") || id.equals("coarse_dirt") || id.equals("grass_block") ? EARTH
                        : id.equals("cobblestone") ? COBBLE : id.equals("obsidian") ? CAST : SOLID;
                kinds.put(state, (short)(kind | rarity << 4));
            }
        }
    }
    public void reset(ClientLevel next) {
        if (task != null) task.cancel(true);
        world = next; copying = submitted = null; task = null;
        known.clear(); dirty.clear(); revisions.clear(); cx = Integer.MIN_VALUE;
    }
    private LevelChunk chunk(int x, int z) {
        return world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }
    public int loadedCount() { return known.size(); }
    public void load(ClientLevel level, LevelChunk chunk) {
        if (world != level || !module.enabled()) return;
        int x = chunk.getPos().x, z = chunk.getPos().z;
        if (cx != Integer.MIN_VALUE && (Math.abs(x - cx) > radius || Math.abs(z - cz) > radius)) return;
        known.put(chunk.getPos().toLong(), chunk); changed(level, x, z);
    }
    public void changed(ClientLevel level, int x, int z) {
        if (world != level || !module.enabled()) return;
        if (cx != Integer.MIN_VALUE && (Math.abs(x - cx) > radius + 1 || Math.abs(z - cz) > radius + 1)) return;
        revisions.put(ChunkPos.asLong(x, z), ++revision);
        for (int oz = -1; oz <= 1; oz++) for (int ox = -1; ox <= 1; ox++) {
            long key = ChunkPos.asLong(x + ox, z + oz);
            if (known.containsKey(key)) { dirty.add(key); module.invalidateTerrain(key); }
        }
    }
    public void unload(ClientLevel level, LevelChunk chunk) {
        if (world != level) return;
        long key = chunk.getPos().toLong(); known.remove(key); dirty.remove(key);
        module.unloaded(key); changed(level, chunk.getPos().x, chunk.getPos().z); revisions.remove(key);
    }
    public void tick(Minecraft mc) {
        if (world != mc.level) reset(mc.level);
        if (world == null || mc.player == null || mc.isPaused()) return;
        long deadline = System.nanoTime() + 750_000;
        if (task != null && task.isDone()) {
            try { if (submitted.valid()) module.acceptTerrain(task.get()); }
            catch (CancellationException ignored) { }
            catch (Exception error) { module.error("V2 terrain scan failed", error); }
            task = null; submitted = null;
        }
        int x = mc.player.blockPosition().getX() >> 4, z = mc.player.blockPosition().getZ() >> 4;
        int r = Math.min(module.radius.get().intValue(), mc.options.renderDistance().get() + 1);
        if (x != cx || z != cz || r != radius) {
            cx = x; cz = z; radius = r; discovery = walkX = walkZ = dx = 0; dz = -1;
            var it = known.keySet().iterator();
            while (it.hasNext()) {
                long key = it.next();
                if (Math.abs((int)key - cx) <= r && Math.abs((int)(key >>> 32) - cz) <= r) continue;
                it.remove(); dirty.remove(key); revisions.remove(key); module.unloaded(key);
            }
            revisions.keySet().removeIf(key -> Math.abs((int)(long)key - cx) > r + 1 || Math.abs((int)(key >>> 32) - cz) > r + 1);
        }
        for (int i = 0; i < 8 && discovery < (2*r+1)*(2*r+1) && System.nanoTime() < deadline; i++, discovery++) {
            int nx = cx + walkX, nz = cz + walkZ;
            if (walkX == walkZ || walkX < 0 && walkX == -walkZ || walkX > 0 && walkX == 1-walkZ) {
                int old = dx; dx = -dz; dz = old;
            }
            walkX += dx; walkZ += dz;
            LevelChunk found = chunk(nx, nz);
            if (found != null && known.get(found.getPos().toLong()) != found) load(world, found);
        }
        if (task != null) return;
        while (System.nanoTime() < deadline) {
            if (copying == null) {
                if (dirty.isEmpty()) return;
                var it = dirty.iterator(); long key = it.next(); it.remove();
                if (!known.containsKey(key)) continue;
                copying = new Snapshot((int)key, (int)(key >>> 32));
            }
            if (!copying.valid()) { copying = null; continue; }
            if (copying.copySlice()) {
                submitted = copying; copying = null;
                Snapshot ready = submitted;
                task = worker.submit(ready::analyze); return;
            }
        }
    }
    private final class Snapshot {
        final int x, z, minY = world.getMinY(), height = world.getMaxY()+1-minY;
        final LevelChunk[] chunks = new LevelChunk[9];
        final long[] versions = new long[9];
        final short[] data = new short[WIDTH * WIDTH * height];
        int cursor;
        Snapshot(int x, int z) {
            this.x = x; this.z = z;
            for (int i = 0; i < 9; i++) {
                int nx = x+i%3-1, nz = z+i/3-1;
                chunks[i] = chunk(nx, nz); versions[i] = revisions.getOrDefault(ChunkPos.asLong(nx,nz), 0L);
            }
        }
        boolean valid() {
            for (int i = 0; i < 9; i++) {
                int nx = x+i%3-1, nz = z+i/3-1;
                if (chunk(nx,nz) != chunks[i] || revisions.getOrDefault(ChunkPos.asLong(nx,nz),0L) != versions[i]) return false;
            }
            return true;
        }
        boolean copySlice() {
            int end = Math.min(data.length, cursor+128);
            while (cursor < end) {
                int index = cursor++, bx = index%WIDTH-1, bz = index/WIDTH%WIDTH-1, by = index/(WIDTH*WIDTH);
                LevelChunk source = chunks[((bz+16)>>4)*3+((bx+16)>>4)];
                if (source != null) data[index] = kinds.getOrDefault(source.getSections()[by>>4]
                        .getBlockState(bx&15,by&15,bz&15), (short)SOLID);
            }
            return cursor == data.length;
        }
        HumanActivityAnalysis.Terrain analyze() {
            return new ActivityTerrainAnalysis(x,z,minY,height,data).analyze();
        }
    }
    @Override public void close() { reset(null); worker.shutdownNow(); }
}
