package com.scrimmy.client.basefinding;

import com.scrimmy.client.basefinding.HumanActivityAnalysis.*;
import com.scrimmy.client.trail.TrailMath;
import com.scrimmy.client.trail.TunnelAnalysis.Sample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HumanActivityAnalysisTest {
    private long pos(int x,int y,int z) { return TrailMath.key(x,y,z); }
    private Terrain terrain(int x,int z,long[] blocks,byte[] rarity) {
        double sum = 0; for (byte r : rarity) sum += r;
        return new Terrain(TrailMath.chunk(x,z),blocks,rarity,sum,0,0,0,0);
    }
    private Features empty() { return new Features(0,0,0,0,0,0,0,0,0,0,List.of()); }
    private Sample sample(int x,int y,int z,int axis) { return new Sample(pos(x,y,z),1<<axis,0,0,0,0); }
    @Test void wallRingAndHoleAreExcludedAndFragmentsUseSixFaceConnectivity() {
        Terrain t = terrain(0,0,new long[]{pos(8,10,8),pos(10,10,8),pos(11,10,8),pos(12,10,8),pos(13,11,8),pos(8,30,8)},new byte[]{5,5,2,0,5,0});
        Features f = HumanActivityAnalysis.features(Map.of(t.key(),t),List.of(sample(8,10,8,1)),Map.of()).get(t.key());
        assertEquals(3,f.leakedBlocks()); assertEquals(2,f.leakedNonNatural()); assertEquals(7,f.leakedRarity());
        assertEquals(List.of(new Fragment(2,1),new Fragment(1,1)),f.fragments());
        assertEquals(1,f.tunnels());
    }
    @Test void crossingShaftsMaskEachOthersShellAndRepeatedSamplesDoNotDuplicateLeakBlocks() {
        Terrain t = terrain(0,0,new long[]{pos(11,10,8),pos(12,10,8)},new byte[]{5,2});
        var samples = List.of(sample(8,10,8,1),sample(8,11,8,1),sample(11,10,8,0));
        Features f = HumanActivityAnalysis.features(Map.of(t.key(),t),samples,Map.of()).get(t.key());
        assertEquals(0,f.leakedBlocks());
    }
    @Test void horizontalTunnelsUseTheirOwnTransverseWallRing() {
        Terrain t = terrain(0,0,new long[]{pos(8,12,8),pos(8,13,8)},new byte[]{5,2});
        Features f = HumanActivityAnalysis.features(Map.of(t.key(),t),List.of(sample(8,10,8,0)),Map.of()).get(t.key());
        assertEquals(List.of(new Fragment(1,1)),f.fragments());
    }
    @Test void fragmentsCrossChunkBordersAndEachChunkReceivesOnlyItsOwnBlocks() {
        Terrain a = terrain(-1,0,new long[]{pos(-1,10,8)},new byte[]{5});
        Terrain b = terrain(0,0,new long[]{pos(0,10,8),pos(1,10,8)},new byte[]{0,2});
        var all = Map.of(a.key(),a,b.key(),b);
        var f = HumanActivityAnalysis.features(all,List.of(sample(-5,10,8,1)),Map.of());
        assertEquals(List.of(new Fragment(3,2)),f.get(a.key()).fragments());
        assertEquals(List.of(new Fragment(3,2)),f.get(b.key()).fragments());
        assertEquals(3,f.values().stream().mapToInt(Features::leakedBlocks).sum());
        assertTrue(HumanActivityAnalysis.features(Map.of(a.key(),a),List.of(),Map.of()).get(a.key()).fragments().isEmpty());
    }
    @Test void smoothUsesAllEightNeighborsOnceAndNeverCreatesUnloadedChunks() {
        Features strong = new Features(40,0,0,0,0,0,0,0,0,0,List.of());
        Map<Long,Features> f = new HashMap<>(); f.put(TrailMath.chunk(0,0),empty());
        for (int z=-1;z<=1;z++) for (int x=-1;x<=1;x++) if(x!=0||z!=0) f.put(TrailMath.chunk(x,z),strong);
        Weights w = new Weights(1,0,0,0,0,.25);
        double strongScore = HumanActivityAnalysis.score(strong,w);
        var ranked = HumanActivityAnalysis.rank(f,w);
        assertEquals(9,ranked.size());
        assertEquals(strongScore*.25,ranked.stream().filter(r->r.key()==0).findFirst().orElseThrow().score(),1e-9);
        var pair = HumanActivityAnalysis.rank(Map.of(0L,empty(),TrailMath.chunk(1,1),strong),w);
        assertEquals(strongScore*.25/8,pair.stream().filter(r->r.key()==0).findFirst().orElseThrow().score(),1e-9);
        for (int i=1;i<ranked.size();i++) assertTrue(ranked.get(i-1).score() >= ranked.get(i).score());
    }
    @Test void eachFeatureCanBeTurnedOffAndScoresStayBounded() {
        Features f = new Features(10000,10000,10000,1000,5000,1,10000,10000,1000,1000,List.of());
        assertEquals(0,HumanActivityAnalysis.score(f,new Weights(0,0,0,0,0,0)));
        for (Weights w : List.of(new Weights(1,0,0,0,0,0),new Weights(0,1,0,0,0,0),new Weights(0,0,1,0,0,0),new Weights(0,0,0,1,0,0),new Weights(0,0,0,0,1,0))) {
            double score = HumanActivityAnalysis.score(f,w); assertTrue(score>0 && score<=100);
        }
        assertEquals(0,HumanActivityAnalysis.score(empty(),new Weights(3,3,3,3,3,.75)));
    }
    @Test void ambiguousGeneratedMaterialsAreWeakerThanGlassConcreteAndSigns() {
        assertEquals(0,HumanActivityAnalysis.rarity("stone")); assertEquals(0,HumanActivityAnalysis.rarity("amethyst_block"));
        for (String rare : List.of("glass","glass_pane","red_concrete","oak_sign","oak_hanging_sign"))
            for (String weak : List.of("oak_planks","bricks","torch","rail")) assertTrue(HumanActivityAnalysis.rarity(rare)>HumanActivityAnalysis.rarity(weak));
    }
}
