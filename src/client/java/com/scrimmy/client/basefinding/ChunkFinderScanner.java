package com.scrimmy.client.basefinding;

import static com.scrimmy.client.basefinding.GeodeAnalysis.*;

import com.scrimmy.client.module.basefinding.Basefinding;
import com.scrimmy.client.basefinding.BaseLikelihood;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;
import java.util.concurrent.*;

/**
 * Main-thread palette filtering/sliced snapshots; one bounded worker analyzes immutable volumes.
 */
public final class ChunkFinderScanner implements AutoCloseable {
    public static final long SNAPSHOT_BUDGET_NANOS = 1_000_000;
    public static final int SCAN_REFRESH_TICKS = 200, DISCOVERY_PER_TICK = 8;
    /** Independent consumers share the detector without enabling the original module. */
    public interface Consumer {
        boolean active();
        int radius();
        void accept(int x, int z, List<Geode> geodes);
        void unloaded(long key);
        void error(String message, Throwable error);
        default boolean periodicRefresh() { return false; }
    }
    private final Consumer module;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "Scrimmy ChunkFinder");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<BlockState, Byte> kinds = new IdentityHashMap<>();
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();
    private final LongOpenHashSet known = new LongOpenHashSet();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
    private ClientLevel world;
    private Snapshot snapshot;
    private Future<List<Geode>> task;
    private Snapshot submitted;
    private long revision;
    private int ticks;
    private int discovery, centerX = Integer.MIN_VALUE, centerZ, range;
    private int discoverX, discoverZ, directionX, directionZ = -1;

    public ChunkFinderScanner(Basefinding module) {
        this(new Consumer() {
            public boolean active() { return module.active(); }
            public int radius() { return module.range.get().intValue() + BaseLikelihood.LOADED_RADIUS; }
            public void accept(int x, int z, List<Geode> found) { module.accept(x, z, found); }
            public void unloaded(long key) { module.unloaded(key); }
            public void error(String message, Throwable error) { module.error(message, error); }
            public boolean periodicRefresh() { return true; }
        });
    }

    public ChunkFinderScanner(Consumer module) {
        this.module = module;
    }

    public void initialize() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int kind =
                        state.isAir()
                                ? AIR
                                : state.is(Blocks.WATER) && state.getFluidState().getAmount() == 8
                                        ? WATER
                                        : block == Blocks.BUDDING_AMETHYST
                                                ? BUDDING
                                                : block == Blocks.AMETHYST_BLOCK
                                                                || block == Blocks.CALCITE
                                                        ? SHELL
                                                        : NATURAL;
                int stage =
                        block == Blocks.SMALL_AMETHYST_BUD
                                ? 0
                                : block == Blocks.MEDIUM_AMETHYST_BUD
                                        ? 1
                                        : block == Blocks.LARGE_AMETHYST_BUD
                                                ? 2
                                                : block == Blocks.AMETHYST_CLUSTER ? 3 : -1;
                if (stage >= 0)
                    kind =
                            STAGE
                                    + state.getValue(AmethystClusterBlock.FACING).get3DDataValue()
                                            * 4
                                    + stage;
                kinds.put(state, (byte) kind);
            }
        }
    }

    public void reset(ClientLevel next) {
        if (task != null) task.cancel(true);
        world = next;
        task = null;
        snapshot = submitted = null;
        revisions.clear();
        known.clear();
        dirty.clear();
        centerX = Integer.MIN_VALUE;
        discovery = 0;
    }

    public void changed(ClientLevel level, int x, int z) {
        if (level != world || !module.active()) return;
        if (centerX != Integer.MIN_VALUE && (Math.abs(x - centerX) > range + 1
                || Math.abs(z - centerZ) > range + 1)) return;
        revisions.put(ChunkPos.asLong(x, z), ++revision);
        // Neighbor snapshots include this chunk's shell and growable faces.
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                long key = ChunkPos.asLong(x + dx, z + dz);
                if (known.contains(key)) dirty.add(key);
            }
    }

    public void load(ClientLevel level, LevelChunk chunk) {
        if (level != world || !module.active()) return;
        if (centerX != Integer.MIN_VALUE && (Math.abs(chunk.getPos().x - centerX) > range
                || Math.abs(chunk.getPos().z - centerZ) > range)) return;
        long key = chunk.getPos().toLong();
        known.add(key);
        changed(level, chunk.getPos().x, chunk.getPos().z);
        dirty.add(key);
    }

    public void unload(ClientLevel level, LevelChunk chunk) {
        if (level != world) return;
        long key = chunk.getPos().toLong();
        known.remove(key);
        dirty.remove(key);
        changed(level, chunk.getPos().x, chunk.getPos().z);
        revisions.remove(key);
        module.unloaded(key);
    }

    private LevelChunk chunk(int x, int z) {
        return world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    public void tick(Minecraft mc) {
        if (world != mc.level) reset(mc.level);
        if (world == null || mc.player == null || !module.active() || mc.isPaused()) return;
        long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
        if (task != null && task.isDone()) {
            try {
                if (submitted.valid()) module.accept(submitted.x, submitted.z, task.get());
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                module.error("ChunkFinder analysis failed", e);
            }
            task = null;
            submitted = null;
        }
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        int r = Math.min(module.radius(), mc.options.renderDistance().get() + 1);
        if (cx != centerX || cz != centerZ || r != range) {
            centerX = cx;
            centerZ = cz;
            range = r;
            discovery = 0;
            var keys = known.iterator();
            while (keys.hasNext()) {
                long key = keys.nextLong();
                if (Math.abs((int) key - cx) <= r && Math.abs((int) (key >>> 32) - cz) <= r) continue;
                keys.remove(); dirty.remove(key); revisions.remove(key); module.unloaded(key);
            }
            var revisionKeys = revisions.keySet().iterator();
            while (revisionKeys.hasNext()) {
                long key = revisionKeys.nextLong();
                if (Math.abs((int) key - cx) > r + 1 || Math.abs((int)(key >>> 32) - cz) > r + 1)
                    revisionKeys.remove();
            }
            discoverX = discoverZ = directionX = 0;
            directionZ = -1;
        }
        // Also discover chunks already present when the feature is enabled. Never request chunks.
        int side = r * 2 + 1;
        for (int i = 0;
                i < DISCOVERY_PER_TICK && discovery < side * side && System.nanoTime() < deadline;
                i++, discovery++) {
            int x = cx + discoverX, z = cz + discoverZ;
            // Walk a center-first square spiral without allocating/sorting chunk offsets.
            if (discoverX == discoverZ
                    || discoverX < 0 && discoverX == -discoverZ
                    || discoverX > 0 && discoverX == 1 - discoverZ) {
                int previous = directionX;
                directionX = -directionZ;
                directionZ = previous;
            }
            discoverX += directionX;
            discoverZ += directionZ;
            LevelChunk found = chunk(x, z);
            if (found != null && !known.contains(found.getPos().toLong())) load(world, found);
        }
        if (++ticks % SCAN_REFRESH_TICKS == 0 && module.periodicRefresh()) for (long key : known) dirty.add(key);
        if (task != null) return;
        while (System.nanoTime() < deadline) {
            if (snapshot == null) {
                if (dirty.isEmpty()) return;
                Iterator<Long> it = dirty.iterator();
                long key = it.next();
                it.remove();
                int x = (int) key, z = (int) (key >>> 32);
                LevelChunk target = chunk(x, z);
                if (target == null || Math.abs(x - cx) > r || Math.abs(z - cz) > r) continue;
                snapshot = new Snapshot(x, z, target);
            }
            if (!snapshot.valid()) {
                snapshot = null;
                continue;
            }
            if (snapshot.copySlice()) {
                Snapshot ready = snapshot;
                snapshot = null;
                if (!ready.hasBudding) {
                    module.accept(ready.x, ready.z, List.of());
                    continue;
                }
                submitted = ready;
                Volume volume = ready.volume();
                task = worker.submit(() -> GeodeAnalysis.analyze(volume));
                return;
            }
        }
    }

    private final class Snapshot {
        final int x, z;
        int minY, maxY;
        final LevelChunk[] chunks = new LevelChunk[9];
        final long[] versions = new long[9];
        final boolean[] candidateSections;
        final IntArrayList buddingStarts = new IntArrayList();
        byte[] data;
        boolean hasBudding;
        int filterSection, part, section, cursor;

        Snapshot(int x, int z, LevelChunk target) {
            this.x = x;
            this.z = z;
            candidateSections = new boolean[target.getSections().length];
            // Palette-filter all heights, including player-placed budding blocks.
            minY = world.getMinY();
            maxY = world.getMaxY() + 1;
            for (int i = 0; i < 9; i++) {
                int nx = x + i % 3 - 1, nz = z + i / 3 - 1;
                chunks[i] = chunk(nx, nz);
                versions[i] = revisions.get(ChunkPos.asLong(nx, nz));
            }
        }

        boolean valid() {
            for (int i = 0; i < 9; i++) {
                int nx = x + i % 3 - 1, nz = z + i / 3 - 1;
                if (chunk(nx, nz) != chunks[i]
                        || revisions.get(ChunkPos.asLong(nx, nz)) != versions[i]) return false;
            }
            return true;
        }

        boolean copySlice() {
            LevelChunk target = chunks[4];
            if (data == null) {
                if (filterSection < target.getSections().length) {
                    int index = filterSection++, y = target.getMinY() + index * 16;
                    if (y < maxY
                            && y + 16 > minY
                            && target.getSections()[index].maybeHas(
                                    s -> s.is(Blocks.BUDDING_AMETHYST))) {
                        candidateSections[index] = true;
                        hasBudding = true;
                    }
                    return false;
                }
                if (!hasBudding || maxY <= minY) return true;
                int first = 0, last = candidateSections.length - 1;
                while (!candidateSections[first]) first++;
                while (!candidateSections[last]) last--;
                // A section halo closes natural shells without copying the whole world height.
                minY = Math.max(minY, target.getMinY() + first * 16 - 16);
                maxY = Math.min(maxY, target.getMinY() + last * 16 + 32);
                data = new byte[48 * 48 * (maxY - minY)];
            }
            if (part == 9) return true;
            LevelChunk source = chunks[part];
            if (source == null) {
                part++;
                section = cursor = 0;
                return false;
            }
            if (section >= source.getSections().length || source.getMinY() + section * 16 >= maxY) {
                part++;
                section = cursor = 0;
                return false;
            }
            int baseY = source.getMinY() + section * 16;
            if (baseY + 16 <= minY) { section++; return false; }
            LevelChunkSection states = source.getSections()[section];
            int end = Math.min(4096, cursor + 128);
            while (cursor < end) {
                int local = cursor++, ly = local >> 8, y = baseY + ly;
                if (y < minY || y >= maxY) continue;
                int bx = part % 3 * 16 + (local & 15), bz = part / 3 * 16 + (local >> 4 & 15);
                int index = ((y - minY) * 48 + bz) * 48 + bx;
                byte kind =
                        states.hasOnlyAir()
                                ? AIR
                                : kinds.getOrDefault(
                                        states.getBlockState(local & 15, ly, local >> 4 & 15),
                                        NATURAL);
                data[index] = kind;
                // Other sections supply only the bounded shell/neighbor halo. Search for
                // budding supports exclusively in the palette-approved target sections.
                if (part == 4 && candidateSections[section] && kind == BUDDING)
                    buddingStarts.add(index);
            }
            if (cursor == 4096) {
                cursor = 0;
                section++;
            }
            return false;
        }

        Volume volume() {
            return new Volume(
                    x * 16 - 16,
                    minY,
                    z * 16 - 16,
                    48,
                    maxY - minY,
                    48,
                    data,
                    x,
                    z,
                    0,
                    false,
                    buddingStarts.toIntArray());
        }
    }

    @Override
    public void close() {
        reset(null);
        worker.shutdownNow();
    }
}
