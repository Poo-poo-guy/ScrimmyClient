package com.scrimmy.client.basefinding;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.scrimmy.client.basefinding.ActivityTerrainAnalysis.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityTerrainAnalysisTest {
    private static final int HEIGHT = 24;
    private short[] blocks = new short[WIDTH*WIDTH*HEIGHT];
    private void put(int x,int y,int z,int kind,int rarity) { blocks[(y*WIDTH+z)*WIDTH+x] = (short)(kind|rarity<<4); }
    private HumanActivityAnalysis.Terrain scan() { return new ActivityTerrainAnalysis(-1,2,-64,HEIGHT,blocks).analyze(); }
    @Test void unknownNeighborsNeverExposeFabricatedBlocksAndBuriedBuiltBlocksAreNotCounted() {
        put(8,8,8,SOLID,5); assertEquals(0,scan().exposedRarity());
        Arrays.fill(blocks,(short)SOLID); put(8,8,8,SOLID,5); assertEquals(0,scan().exposedRarity());
        put(9,8,8,OPEN,0); assertEquals(5,scan().exposedRarity());
        assertTrue(Arrays.stream(scan().exposed()).anyMatch(p -> com.scrimmy.client.trail.TrailMath.y(p)==-56));
    }
    @Test void neighborHaloCanExposeBoundaryBlocksWithoutCountingNeighborEvidence() {
        put(1,8,8,SOLID,5); put(0,8,8,OPEN,0); assertEquals(5,scan().exposedRarity());
        put(0,8,9,SOLID,5); assertEquals(5,scan().exposedRarity());
    }
    @Test void isolatedTallDirtAndCobbleColumnsArePillarsButSolidCliffsAreNot() {
        Arrays.fill(blocks,(short)OPEN);
        for(int y=0;y<7;y++) { put(7,y,7,EARTH,0); put(12,y,12,COBBLE,0); }
        assertEquals(2,scan().pillars());
        Arrays.fill(blocks,(short)EARTH); assertEquals(0,scan().pillars());
    }
    @Test void flatAreasAndStraightTerracesRequireBroadPatchesAndExcludeSubmergedFlats() {
        Arrays.fill(blocks,(short)OPEN);
        for(int z=1;z<=16;z++)for(int x=1;x<=16;x++)put(x,4,z,EARTH,0);
        assertTrue(scan().flattened()>0);
        for(int z=1;z<=16;z++)for(int x=1;x<=16;x++)put(x,5,z,WATER,0);
        assertEquals(0,scan().flattened());
        Arrays.fill(blocks,(short)OPEN);
        for(int z=1;z<=16;z++)for(int x=1;x<=16;x++)put(x,x<8?4:5,z,EARTH,0);
        assertTrue(scan().terraces()>0);
    }
    @Test void fluidAdjacentCobbleAndTallDryObsidianColumnsAreCastCandidates() {
        Arrays.fill(blocks,(short)OPEN);
        for(int y=0;y<5;y++) { put(6,y,6,COBBLE,0); put(5,y,6,WATER,0); }
        for(int y=0;y<9;y++)put(12,y,12,CAST,0);
        assertEquals(2,scan().casts());
    }
}
