package com.scrimmy.client.esp;

import com.scrimmy.client.module.visual.BlockESPModule;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BlockEspCacheTest {
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
    @Test void nearestSelectionHandlesCapChangesAndRecyclesFadingVisuals() throws Exception {
        BlockESPModule module = new BlockESPModule();
        BlockEspCache cache = module.cache;
        Class<?> validityType = Class.forName(BlockEspCache.class.getName() + "$Validity");
        Constructor<?> constructor = validityType.getDeclaredConstructor(); constructor.setAccessible(true);
        Method offer = BlockEspCache.class.getDeclaredMethod("offer", long.class, Block.class, validityType, double.class); offer.setAccessible(true);
        Method publish = BlockEspCache.class.getDeclaredMethod("publish"); publish.setAccessible(true);
        Field cap = BlockEspCache.class.getDeclaredField("selectionCap"); cap.setAccessible(true);
        Field pool = BlockEspCache.class.getDeclaredField("freeCount"); pool.setAccessible(true);
        for (int cycle = 0; cycle < 20; cycle++) {
            module.maxBlocks.set(64.0); cap.setInt(cache, 64);
            Object validity = constructor.newInstance();
            int[] order = new int[1000];
            for (int i = 0; i < order.length; i++) order[i] = i + 1;
            Random random = new Random(cycle);
            for (int i = order.length - 1; i > 0; i--) { int j = random.nextInt(i + 1), old = order[i]; order[i] = order[j]; order[j] = old; }
            for (int id : order) offer.invoke(cache, (long)id, null, validity, (double)id*id);
            // A live settings edit must not publish more than the new cap.
            module.maxBlocks.set(16.0); publish.invoke(cache);
            assertEquals(16, cache.size());
            Set<Long> positions = new HashSet<>();
            for (int i = 0; i < cache.size(); i++) { positions.add(cache.visual(i).entry.pos); cache.visual(i).alpha = .4; }
            for (long i = 1; i <= 16; i++) assertTrue(positions.contains(i));
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
