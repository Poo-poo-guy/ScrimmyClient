package com.scrimmy.client.esp;

import com.scrimmy.client.module.visual.BlockESPModule;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BlockEspCacheTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static final Class<?> CHUNK;
    static {
        try { CHUNK = Class.forName(BlockEspCache.class.getName() + "$Chunk"); }
        catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }
    @SuppressWarnings("unchecked")
    private static Object chunk(BlockEspCache cache, int x, int z) throws Exception {
        Constructor<?> constructor = CHUNK.getDeclaredConstructor(LevelChunk.class, int.class, int.class, int.class, int.class, double.class);
        constructor.setAccessible(true);
        Object chunk = constructor.newInstance(null, x, z, -64, 24, 64.0);
        ((Long2ObjectOpenHashMap<Object>)field(cache, "chunks")).put(ChunkPos.asLong(x, z), chunk);
        ((ArrayList<Object>)field(cache, "chunkList")).add(chunk);
        return chunk;
    }
    private static void remember(BlockEspCache cache, Object chunk, int local, Block block) throws Exception {
        invoke(cache, "setMatch", new Class<?>[]{CHUNK, int.class, int.class, Block.class}, chunk, 8, local, block);
    }
    @SuppressWarnings("unchecked")
    private static Map<Block, Integer> colors(BlockESPModule module) throws Exception {
        return (Map<Block, Integer>)field(module, "colors");
    }
    @Test void movingDrawLimitsAndEmptyDrawSetsNeverEvictKnowledge() throws Exception {
        BlockESPModule module = new BlockESPModule(); BlockEspCache cache = module.cache;
        Object first = chunk(cache, -2, 3), second = chunk(cache, 1, -4);
        for (int i = 0; i < 1000; i++) remember(cache, first, i, Blocks.CHEST);
        remember(cache, second, 4095, Blocks.SPAWNER);
        assertEquals(1001, cache.cachedCount());
        invoke(cache, "recenter", new Class<?>[]{int.class, int.class, int.class}, 10000, -10000, 2);
        module.range.set(16.0); module.maxBlocks.set(64.0);
        // Publishing an empty draw selection is what used to be confused with losing detections.
        invoke(cache, "publish", new Class<?>[]{});
        assertEquals(0, cache.size());
        assertEquals(1001, cache.cachedCount());
        assertEquals(2, ((Long2ObjectOpenHashMap<?>)field(cache, "chunks")).size());
        assertEquals(1000, ((Long2ObjectOpenHashMap<?>)field(first, "blocks")).size());
        invoke(cache, "recenter", new Class<?>[]{int.class, int.class, int.class}, -2, 3, 40);
        assertEquals(1001, cache.cachedCount());
    }
    @Test void realBlockUpdatesReplaceTypeAndRemoveOnlyTheirPosition() throws Exception {
        BlockESPModule module = new BlockESPModule(); BlockEspCache cache = module.cache;
        colors(module).put(Blocks.CHEST, 0xff55b88a); colors(module).put(Blocks.SPAWNER, 0xffbb859f);
        Object chunk = chunk(cache, -2, 3);
        BlockPos pos = new BlockPos(-32, 64, 48), neighbor = pos.offset(1, 0, 0);
        cache.changed(null, pos, Blocks.CHEST.defaultBlockState());
        cache.changed(null, neighbor, Blocks.CHEST.defaultBlockState());
        assertEquals(2, cache.cachedCount());
        cache.changed(null, pos, Blocks.SPAWNER.defaultBlockState());
        assertEquals(2, cache.cachedCount());
        assertSame(Blocks.SPAWNER, ((Long2ObjectOpenHashMap<?>)field(chunk, "blocks")).get(pos.asLong()));
        cache.changed(null, pos, Blocks.AIR.defaultBlockState());
        assertEquals(1, cache.cachedCount());
        assertNull(((Long2ObjectOpenHashMap<?>)field(chunk, "blocks")).get(pos.asLong()));
        assertSame(Blocks.CHEST, ((Long2ObjectOpenHashMap<?>)field(chunk, "blocks")).get(neighbor.asLong()));
        cache.changed(null, new BlockPos(0, 64, 0), Blocks.CHEST.defaultBlockState());
        assertEquals(1, cache.cachedCount(), "An update for an untracked chunk must not invent a loaded chunk");
    }
    @Test void unloadingAndReloadingAChunkDoesNotDisturbItsNeighborAndClearDropsAll() throws Exception {
        BlockEspCache cache = new BlockESPModule().cache;
        Object old = chunk(cache, 0, 0), neighbor = chunk(cache, 1, 0);
        remember(cache, old, 0, Blocks.CHEST); remember(cache, neighbor, 1, Blocks.SPAWNER);
        Object validity = field(old, "validity");
        invoke(cache, "discard", new Class<?>[]{CHUNK}, old);
        assertEquals(1, cache.cachedCount());
        assertEquals(false, field(validity, "active"));
        Object reloaded = chunk(cache, 0, 0);
        remember(cache, reloaded, 2, Blocks.CHEST);
        assertEquals(2, cache.cachedCount());
        assertEquals(1, ((Long2ObjectOpenHashMap<?>)field(neighbor, "blocks")).size());
        assertEquals(1, ((Long2ObjectOpenHashMap<?>)field(reloaded, "blocks")).size());
        cache.clear(); assertEquals(0, cache.cachedCount()); assertEquals(false, field(field(reloaded, "validity"), "active"));
    }
    @Test void stableSelectionDoesNotDirtyGeometryAndFadesDoNotNeedFrameMutations() throws Exception {
        BlockEspCache cache = new BlockESPModule().cache;
        Object chunk = chunk(cache, 0, 0), validity = field(chunk, "validity");
        Field cap = BlockEspCache.class.getDeclaredField("selectionCap"); cap.setAccessible(true); cap.setInt(cache, 64);
        invoke(cache, "offer", new Class<?>[]{long.class, Block.class, validity.getClass(), double.class}, 1L, Blocks.CHEST, validity, 1.0);
        invoke(cache, "publish", new Class<?>[]{});
        long revision = cache.renderRevision();
        BlockEspCache.Visual visual = cache.visual(0);
        assertEquals(0, visual.opacity(visual.changedAt), .0001);
        assertTrue(visual.opacity(visual.changedAt + .3f) > .98);
        for (int i = 0; i < 20; i++) invoke(cache, "publish", new Class<?>[]{});
        assertEquals(revision, cache.renderRevision());
        assertTrue(visual.wanted);
        cache.removeVisual(0);
        assertTrue(cache.renderRevision() > revision);
    }

    @Test void bitCursorCrossesWordsAndStopsAtSectionBoundary() {
        long[] bits = new long[64];
        bits[0] = 1L | Long.MIN_VALUE; bits[1] = 1; bits[63] = Long.MIN_VALUE;
        assertEquals(0, BlockEspCache.nextSetBit(bits, 0));
        assertEquals(63, BlockEspCache.nextSetBit(bits, 1));
        assertEquals(64, BlockEspCache.nextSetBit(bits, 64));
        assertEquals(4095, BlockEspCache.nextSetBit(bits, 65));
        assertEquals(-1, BlockEspCache.nextSetBit(bits, 4096));
        assertEquals(-1, BlockEspCache.nextSetBit(new long[64], 0));
    }
    /** Feed synthetic discoveries through the real bounded selection and lifecycle, without a world/GL context. */
    @Test void nearestSelectionUsesFixedCapAndRecyclesFadingVisuals() throws Exception {
        BlockESPModule module = new BlockESPModule();
        BlockEspCache cache = module.cache;
        Class<?> validityType = Class.forName(BlockEspCache.class.getName() + "$Validity");
        Constructor<?> constructor = validityType.getDeclaredConstructor(); constructor.setAccessible(true);
        Method offer = BlockEspCache.class.getDeclaredMethod("offer", long.class, Block.class, validityType, double.class); offer.setAccessible(true);
        Method publish = BlockEspCache.class.getDeclaredMethod("publish"); publish.setAccessible(true);
        Field cap = BlockEspCache.class.getDeclaredField("selectionCap"); cap.setAccessible(true);
        Field pool = BlockEspCache.class.getDeclaredField("freeCount"); pool.setAccessible(true);
        for (int cycle = 0; cycle < 20; cycle++) {
            cap.setInt(cache, BlockESPModule.MAX_BLOCKS);
            Object validity = constructor.newInstance();
            int[] order = new int[1000];
            for (int i = 0; i < order.length; i++) order[i] = i + 1;
            Random random = new Random(cycle);
            for (int i = order.length - 1; i > 0; i--) { int j = random.nextInt(i + 1), old = order[i]; order[i] = order[j]; order[j] = old; }
            for (int id : order) offer.invoke(cache, (long)id, null, validity, (double)id*id);
            // Internal selection remains capped when more matches are discovered.
            publish.invoke(cache);
            assertEquals(BlockESPModule.MAX_BLOCKS, cache.size());
            Set<Long> positions = new HashSet<>();
            for (int i = 0; i < cache.size(); i++) { positions.add(cache.visual(i).entry.pos); cache.visual(i).alpha = .4; }
            for (long i = 1; i <= BlockESPModule.MAX_BLOCKS; i++) assertTrue(positions.contains(i));
            Field alive = BlockEspCache.Entry.class.getDeclaredField("alive"); alive.setAccessible(true);
            BlockEspCache.Visual changed = cache.visual(0); alive.setBoolean(changed.entry, false);
            publish.invoke(cache);
            assertFalse(changed.wanted, "A block replaced at the same position must finish fading out");
            cache.rebuild();
            for (int i = 0; i < cache.size(); i++) assertFalse(cache.visual(i).wanted);
            while (cache.size() > 0) cache.removeVisual(0);
            assertEquals(BlockEspCache.MAX_RENDERED * 2, pool.getInt(cache));
            cache.clear(); assertEquals(0, cache.cachedCount()); assertNull(cache.world());
        }
    }
}
