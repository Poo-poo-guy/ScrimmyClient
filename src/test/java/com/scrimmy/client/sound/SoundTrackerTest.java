package com.scrimmy.client.sound;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoundTrackerTest {
    private static final SoundTracker.Weights WEIGHTS = new SoundTracker.Weights(1, 2, 4, 12, 1, 1.5, 4, 10_000, 120_000);
    private static final BlockPos CHEST = new BlockPos(-1, 64, -17);
    private void tick(SoundTracker tracker, long now) { tracker.tick(now, 30_000, 5_000, 256, WEIGHTS); }
    private SoundTracker.Source hear(SoundTracker tracker, BlockPos pos, long time) {
        return tracker.hear(pos, "minecraft:block.chest.open", "blocks", false, time, 256);
    }
    @Test void packetsFloorNegativeCoordinatesAndMergeAtOneBlock() {
        assertEquals(CHEST, BlockPos.containing(-.125,64.75,-16.125));
        SoundTracker tracker = new SoundTracker();
        var source = hear(tracker, CHEST, 0);
        var again = tracker.hear(CHEST, "minecraft:block.chest.close", "blocks", false, 1500, 256);
        assertSame(source, again); assertEquals(1, tracker.blocks().size());
        assertEquals(2, source.hits); assertEquals(0, source.firstHeard); assertEquals(1500, source.lastHeard);
        assertEquals("minecraft:block.chest.close", source.soundId); assertEquals(-.5, source.x);
    }
    @Test void onlyPositiveBlockEvidenceCanConfirmSounds() {
        assertTrue(SoundTracker.matches("minecraft:block.chest.open", "minecraft:trapped_chest"));
        assertTrue(SoundTracker.matches("block.piston.extend", "sticky_piston"));
        assertTrue(SoundTracker.matches("block.wooden_door.close", "oak_door"));
        assertTrue(SoundTracker.matches("block.wooden_trapdoor.close", "spruce_trapdoor"));
        assertTrue(SoundTracker.matches("block.shulker_box.open", "red_shulker_box"));
        assertTrue(SoundTracker.matches("block.enchantment_table.use", "enchanting_table"));
        assertFalse(SoundTracker.matches("block.chest.open", "air"));
        assertFalse(SoundTracker.matches("block.chest.open", "unloaded"));
        assertFalse(SoundTracker.matches("block.chest.open", "barrel"));
        assertFalse(SoundTracker.matches("block.wooden_door.open", "iron_door"));
        assertFalse(SoundTracker.matches("block.wooden_door.open", "oak_trapdoor"));
        assertFalse(SoundTracker.matches("entity.generic.explode", "tnt"));
    }
    @Test void repeatedActivityRisesThenDecaysAndExpiryFades() {
        SoundTracker tracker = new SoundTracker(); var source = hear(tracker, CHEST, 0); tick(tracker, 0);
        assertEquals(0, SoundTracker.severity(source.score, 4, 12));
        for (int i=1;i<=3;i++) hear(tracker, CHEST, i*1000);
        tick(tracker, 3000); assertEquals(1, SoundTracker.severity(source.score, 4, 12));
        tick(tracker, 14000); assertEquals(1, source.score);
        tick(tracker, 28000); assertEquals(1, source.opacity);
        tick(tracker, 30500); assertEquals(.5, source.opacity, .00001);
        tick(tracker, 33000); assertTrue(tracker.blocks().isEmpty()); assertTrue(tracker.chunks().isEmpty());
    }
    @Test void distinctBlocksClusterByChunkWhileEntityMarkersStaySeparate() {
        SoundTracker tracker = new SoundTracker();
        for (int i=0;i<9;i++) hear(tracker, new BlockPos(-16+i,64,-17), 0);
        var entity = tracker.hear(new BlockPos(-16,64,-17), "minecraft:entity.cow.ambient", "neutral", true, 0, 256);
        tick(tracker, 0);
        assertEquals(9, tracker.blocks().size()); assertEquals(1, tracker.entities().size());
        var chunk = tracker.chunks().iterator().next(); assertEquals(-1, chunk.x); assertEquals(-2, chunk.z);
        assertEquals(9, chunk.sources); assertEquals(2, SoundTracker.severity(chunk.score,4,12));
        assertEquals(1, entity.score); assertFalse(entity.confirmed);
        tracker.clearEntities(); assertTrue(tracker.entities().isEmpty()); assertEquals(9, tracker.blocks().size());
    }
    @Test void sustainedActivityOutlastsRepeatWindowAndCriticalSoundsStartRed() {
        SoundTracker tracker = new SoundTracker(); var source = hear(tracker, CHEST, 0);
        for (int i=20;i<=120;i+=20) { hear(tracker, CHEST, i*1000); tick(tracker, i*1000); }
        assertEquals(5, source.score); // One recent hit plus sustained bonus, rather than lifetime repeats.
        tracker.hear(new BlockPos(32,64,32),"minecraft:block.bell.use","blocks",false,120000,256);
        tick(tracker,120000);
        assertTrue(tracker.blocks().stream().anyMatch(s -> SoundTracker.severity(s.score,4,12)==2));
    }
    @Test void capacityEvictsFirstHeardEvenIfRefreshedAndBurstMemorySaturates() {
        SoundTracker tracker = new SoundTracker(); var oldest = hear(tracker, CHEST,0);
        hear(tracker,new BlockPos(2,64,2),1000); hear(tracker,CHEST,2000);
        tracker.hear(new BlockPos(3,64,3),"block.chest.open","blocks",false,3000,2);
        assertFalse(tracker.blocks().contains(oldest)); assertEquals(2, tracker.blocks().size());
        for(int i=0;i<2000;i++) hear(tracker,new BlockPos(3,64,3),3000);
        tick(tracker,3000);
        assertTrue(tracker.blocks().stream().allMatch(s -> s.score < 150));
        tracker.trim(1); assertEquals(1,tracker.blocks().size()); tracker.clear(); assertTrue(tracker.blocks().isEmpty());
    }
    @Test void angleSelectionAndDistanceFadeDoNotDependOnTerrain() {
        double cosine=Math.cos(Math.toRadians(4));
        assertTrue(SoundMath.insideCone(0,0,40,0,0,1,cosine));
        assertTrue(SoundMath.insideCone(2,0,40,0,0,1,cosine));
        assertFalse(SoundMath.insideCone(4,0,40,0,0,1,cosine));
        assertFalse(SoundMath.insideCone(0,0,-40,0,0,1,cosine));
        assertFalse(SoundMath.insideCone(0,0,0,0,0,1,cosine));
        assertEquals(1,SoundMath.distanceFade(100,256,48));
        assertEquals(.5,SoundMath.distanceFade(232,256,48));
        assertEquals(0,SoundMath.distanceFade(256,256,48));
        assertEquals(1,SoundMath.distanceFade(250,256,0));
    }
}
