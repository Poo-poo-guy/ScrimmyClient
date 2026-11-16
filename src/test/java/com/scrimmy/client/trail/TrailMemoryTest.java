package com.scrimmy.client.trail;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrailMemoryTest {
    private TunnelAnalysis.Chunk chunk() {
        List<TunnelAnalysis.Sample> samples=new ArrayList<>();
        for(int x=0;x<16;x++)samples.add(new TunnelAnalysis.Sample(TrailMath.key(x,-20,8),1,0,0,0,0));
        return new TunnelAnalysis.Chunk(TrailMath.chunk(0,0),samples);
    }
    @Test void visitsAndEvidenceSurviveSnapshotsAndDisappearOnSessionClear() {
        TrailMemory memory=new TrailMemory(); assertTrue(memory.put(chunk()));
        var network=TunnelNetwork.build(memory.snapshot(),12);
        assertTrue(memory.visit(network,4.5,-19.5,8.5)); assertTrue(memory.visited(TrailMath.key(4,-20,8)));
        assertEquals(1,memory.snapshot().size()); assertFalse(memory.put(chunk()));
        assertTrue(memory.visited(TrailMath.key(4,-20,8)));
        memory.put(new TunnelAnalysis.Chunk(TrailMath.chunk(0,0),List.of()));
        assertTrue(TunnelNetwork.build(memory.snapshot(),12).paths().isEmpty());
        memory.clear(); assertFalse(memory.visited(TrailMath.key(4,-20,8))); assertEquals(0,memory.chunkCount());
    }
    @Test void historyIsBounded() {
        TrailMemory memory=new TrailMemory();
        for(int i=0;i<TrailMemory.MAX_CHUNKS+10;i++)memory.put(new TunnelAnalysis.Chunk(TrailMath.chunk(i,0),List.of()));
        assertEquals(TrailMemory.MAX_CHUNKS,memory.chunkCount());
    }
    @Test void packedCoordinatesAndCurvesHandleNegativePositions() {
        long key=TrailMath.key(-30000000,-64,-12345);
        assertEquals(-30000000,TrailMath.x(key)); assertEquals(-64,TrailMath.y(key)); assertEquals(-12345,TrailMath.z(key));
        assertEquals(2,TrailMath.spline(1,2,3,4,0)); assertEquals(3,TrailMath.spline(1,2,3,4,1));
        assertEquals(2.5,TrailMath.spline(1,2,3,4,.5));
        assertEquals(0,TrailMath.bearing(0,10,0),1e-9); assertEquals(-90,TrailMath.bearing(10,0,0));
        assertEquals(0,TrailMath.fade(100,96,.65)); assertEquals(1,TrailMath.fade(10,96,.65));
    }
}
