package com.scrimmy.client.render;

import com.scrimmy.client.esp.BlockEspCache;
import net.minecraft.core.BlockPos;

/** Selection has no camera input: only player distance, valid cached blocks and the nearest cap. */
public final class BlockEspDrawList {
    private BlockEspDrawList() {}
    public static int select(BlockEspCache cache, double x, double y, double z, double range, int cap, long[] order) {
        int count = 0, limit = Math.min(cap, order.length);
        if (limit == 0) return 0;
        for (int i = 0; i < cache.size(); i++) {
            BlockEspCache.Visual visual = cache.visual(i);
            if (!visual.entry.alive()) continue;
            long pos = visual.entry.pos;
            double dx = BlockPos.getX(pos)+.5-x, dy = BlockPos.getY(pos)+.5-y, dz = BlockPos.getZ(pos)+.5-z;
            double distance = dx*dx + dy*dy + dz*dz;
            if (distance > range*range) continue;
            long key = ((long)Float.floatToRawIntBits((float)distance) << 32) | (cache.size()-1-i);
            if (count < limit) {
                int slot = count++;
                while (slot > 0) {
                    int parent = (slot-1)/2;
                    if (order[parent] >= key) break;
                    order[slot] = order[parent]; slot = parent;
                }
                order[slot] = key;
            } else if (key < order[0]) siftDown(order, 0, count, key);
        }
        // In-place heap sort: Arrays.sort(long[]) can allocate a merge-run scratch array.
        for (int end = count-1; end > 0; end--) {
            long key = order[end]; order[end] = order[0]; siftDown(order, 0, end, key);
        }
        return count;
    }
    private static void siftDown(long[] order, int slot, int count, long key) {
        while (slot*2+1 < count) {
            int child = slot*2+1;
            if (child+1 < count && order[child+1] > order[child]) child++;
            if (order[child] <= key) break;
            order[slot] = order[child]; slot = child;
        }
        order[slot] = key;
    }
}
