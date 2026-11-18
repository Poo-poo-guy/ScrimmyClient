package com.scrimmy.client.trail;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.scrimmy.client.trail.TunnelAnalysis.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelAnalysisTest {
    private static final int WIDTH = 64, HEIGHT = 32;
    private byte[] blocks = solid();
    private byte[] solid() { byte[] b = new byte[WIDTH * WIDTH * HEIGHT]; Arrays.fill(b, SOLID); return b; }
    private void put(int x, int y, int z, byte kind) {
        if (x < -8 || y < -8 || z < -8 || x >= 56 || y >= 24 || z >= 56) return;
        blocks[((y + 8) * WIDTH + z + 8) * WIDTH + x + 8] = kind;
    }
    private void box(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) put(x,y,z,AIR);
    }
    private void x(int from, int to, int y, int z) { box(from, y-1, z-1, to, y+1, z+1); }
    private void z(int from, int to, int x, int y) { box(x-1,y-1,from,x+1,y+1,to); }
    private List<Chunk> chunks() {
        List<Chunk> chunks = new ArrayList<>();
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++)
            chunks.add(analyze(new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,x,z,blocks,null)));
        return chunks;
    }
    private TunnelNetwork.Network network() { return TunnelNetwork.build(chunks(), 12); }
    private long markers(TunnelNetwork.Network n, TunnelNetwork.Kind kind) { return n.markers().stream().filter(m->m.kind()==kind).count(); }

    @Test void straightTunnelCrossesChunkBordersAndSplitsAtNeitherBorder() {
        x(2,45,4,8); var n=network();
        assertEquals(1,n.paths().size()); assertEquals(44,n.points().size());
        assertEquals(2,markers(n,TunnelNetwork.Kind.END));
    }
    @Test void shortPocketsAndLargeIrregularCavesAreRejected() {
        box(2,3,7,4,5,9); box(12,0,0,30,12,22);
        assertTrue(network().paths().isEmpty());
    }
    @Test void rightAngleBendJoinsAcrossItsOpenCorner() {
        x(2,29,4,8); z(8,35,29,4); var n=network();
        assertEquals(1,n.paths().size()); assertEquals(2,n.markers().size());
        assertTrue(n.points().contains(TrailMath.key(29,4,8)));
    }
    @Test void crossingHasOneJunctionAndFourSeparateSegments() {
        x(2,45,4,24); z(2,45,24,4); var n=network();
        assertEquals(1,markers(n,TunnelNetwork.Kind.JUNCTION)); assertEquals(4,n.paths().size());
        assertEquals(4,markers(n,TunnelNetwork.Kind.END));
    }
    @Test void staircaseAndVerticalShaftAreContinuous() {
        for(int i=2;i<=40;i++) x(i,i,(i-2)/3,8);
        var n=network();
        assertEquals(1,n.paths().size());
        long[] stair=n.paths().getFirst().points();
        assertTrue(n.points().size()>=35);
        assertTrue(Math.min(TrailMath.x(stair[0]),TrailMath.x(stair[stair.length-1]))<=4);
        assertTrue(Math.max(TrailMath.x(stair[0]),TrailMath.x(stair[stair.length-1]))>=38);
        blocks=solid(); box(7,-5,7,9,20,9); n=network();
        assertEquals(1,n.paths().size()); assertTrue(n.points().stream().allMatch(k->TrailMath.x(k)==8&&TrailMath.z(k)==8));
    }
    @Test void oneWaterOrPlacedBlockDoesNotBreakThePathAndLavaWarns() {
        x(2,45,4,8); put(10,4,8,WATER); put(20,4,8,SOLID); put(30,4,8,LAVA);
        var n=network(); assertEquals(1,n.paths().size());
        assertTrue(markers(n,TunnelNetwork.Kind.LAVA)>0); assertTrue(markers(n,TunnelNetwork.Kind.OBSTACLE)>0);
    }
    @Test void aRoomAndAnUnloadedBoundaryHaveDistinctLeadTypes() {
        x(2,25,4,8); box(26,0,0,44,12,22); var n=network();
        assertEquals(1,markers(n,TunnelNetwork.Kind.ROOM));
        blocks=solid(); x(2,15,4,8);
        for(int y=-8;y<24;y++)for(int z=-8;z<56;z++)for(int x=16;x<56;x++)put(x,y,z,UNKNOWN);
        var only=analyze(new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,0,0,blocks,null));
        n=TunnelNetwork.build(List.of(only),12); assertEquals(1,markers(n,TunnelNetwork.Kind.FRONTIER));
        assertTrue(n.tunnels().getFirst().frontier());assertEquals(1,n.frontiers().size());
        var strict=analyze(new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,0,0,blocks,null),false);
        var normal=TunnelNetwork.build(List.of(strict),12,false);
        assertTrue(normal.tunnels().getFirst().frontier());assertEquals(1,normal.frontiers().size());
        assertTrue(normal.markers().isEmpty());
    }
    @Test void anOpenFloorWarnsAboutADeepDrop() {
        x(2,45,4,8); box(20,-5,8,20,2,8);
        assertTrue(markers(network(),TunnelNetwork.Kind.DROP)>0);
    }
    @Test void aFullyBlockedLavaSectionWarnsAtTheApproach() {
        x(2,45,4,8);
        for(int y=3;y<=5;y++)for(int z=7;z<=9;z++)put(30,y,z,LAVA);
        var n=network();
        assertTrue(markers(n,TunnelNetwork.Kind.LAVA)>0);
    }
    @Test void diagonalStepsAndAVerticalTurnRemainConnected() {
        for(int i=2;i<=35;i++) x(i,i,(i-2)/6,8+(i-2)/2);
        var n=network(); assertEquals(1,n.paths().size());
        blocks=solid(); x(2,29,0,8);box(28,0,7,30,20,9);
        n=network(); assertEquals(1,n.paths().size());
    }
    @Test void aStairLipAtATurnDoesNotInventAnotherJunction() {
        x(2,45,0,8);z(8,24,8,0);
        for(int i=8;i<=22;i++)x(i,i,(i-8)/3,24);
        var n=network();assertEquals(1,markers(n,TunnelNetwork.Kind.JUNCTION));
    }
    @Test void aClosedLoopHasAContinuousPathWithoutFalseDeadEnds() {
        x(4,36,2,4);x(4,36,2,36);z(4,36,4,2);z(4,36,36,2);
        var n=network(); assertEquals(1,n.paths().size());
        long[] loop=n.paths().getFirst().points();assertEquals(loop[0],loop[loop.length-1]);
        assertEquals(0,markers(n,TunnelNetwork.Kind.END));
    }
    @Test void missingUnrelatedSectionsCannotCreateFalseCandidates() {
        x(2,45,4,8); boolean[] sections={false,false};
        assertTrue(analyze(new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,0,0,blocks,sections)).samples().isEmpty());
    }

    private TunnelNetwork.Network mode(boolean smart,int minimum) {
        List<Chunk> chunks=new ArrayList<>();
        for(int z=0;z<3;z++)for(int x=0;x<3;x++)
            chunks.add(analyze(new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,x,z,blocks,null),smart));
        return TunnelNetwork.build(chunks,minimum,smart);
    }
    @Test void normalKeepsTurnsAndStairsWithoutSmartFeatures() {
        x(2,29,4,8);z(8,35,29,4);
        var n=mode(false,12);assertEquals(1,n.paths().size());assertTrue(n.markers().isEmpty());assertTrue(n.plugs().isEmpty());
        assertEquals(1,n.tunnels().size());
    }
    @Test void smartBridgesFlatRowsColumnsAndFullSlabsAcrossAChunkBorder() {
        for(int slab=0;slab<3;slab++) {
            blocks=solid();x(2,45,4,8);
            if(slab==0)for(int z=7;z<=9;z++)put(16,3,z,SOLID);
            if(slab==1)for(int y=3;y<=5;y++)put(16,y,8,SOLID);
            if(slab==2)for(int y=3;y<=5;y++)for(int z=7;z<=9;z++)put(16,y,z,SOLID);
            var n=mode(true,12);assertEquals(1,n.paths().size(),"slab "+slab);
            assertEquals(1,markers(n,TunnelNetwork.Kind.PLUG));assertEquals(2,markers(n,TunnelNetwork.Kind.END));
            assertEquals(2,mode(false,12).paths().size());
        }
    }
    @Test void plugLookAheadRejectsScatteredOffsetAndThickBlockages() {
        x(2,45,4,8);put(16,3,7,SOLID);put(16,5,9,SOLID);put(16,4,8,SOLID);
        assertEquals(0,markers(mode(true,12),TunnelNetwork.Kind.PLUG));
        blocks=solid();x(2,15,4,8);x(17,45,4,10);
        assertEquals(0,markers(mode(true,12),TunnelNetwork.Kind.PLUG));
        blocks=solid();x(2,45,4,8);
        for(int bx=16;bx<=18;bx++)for(int y=3;y<=5;y++)for(int z=7;z<=9;z++)put(bx,y,z,SOLID);
        assertEquals(0,markers(mode(true,12),TunnelNetwork.Kind.PLUG));
    }
    @Test void twoLayerPlugAndPlugAfterTurnUseTheLocalHeading() {
        x(2,29,4,8);z(8,45,29,4);
        for(int z=24;z<=25;z++)for(int y=3;y<=5;y++)for(int x=28;x<=30;x++)put(x,y,z,SOLID);
        var n=mode(true,12);assertEquals(1,n.paths().size());assertFalse(n.plugs().isEmpty());
        assertEquals(1,markers(n,TunnelNetwork.Kind.PLUG));
    }
    @Test void repeatedShortZigZagsAreOneTunnelAndNeverRooms() {
        int x=3,z=5;
        for(int i=0;i<10;i++){x(x,x+3,4,z);x+=3;z(z,z+3,x,4);z+=3;}
        var n=mode(true,35);
        assertEquals(1,n.paths().size());assertEquals(1,n.tunnels().size());
        assertEquals(0,markers(n,TunnelNetwork.Kind.ROOM));assertEquals(0,markers(n,TunnelNetwork.Kind.JUNCTION));
        assertTrue(n.tunnels().getFirst().length()>45);
    }
    @Test void alternatingVerticalStepsUsePathLength() {
        for(int i=3;i<43;i++)x(i,i,(i%4<2?3:4),8);
        var n=mode(true,40);assertEquals(1,n.tunnels().size());assertTrue(n.tunnels().getFirst().length()>=40);
        assertEquals(0,markers(n,TunnelNetwork.Kind.ROOM));
    }
    @Test void normalFilteringRememberedSmartEvidenceCannotBridgeAPlug() {
        x(2,45,4,8);for(int z=7;z<=9;z++)put(16,3,z,SOLID);
        var cached=chunks();var n=TunnelNetwork.build(cached,12,false);
        assertEquals(2,n.paths().size());assertTrue(n.plugs().isEmpty());assertTrue(n.markers().isEmpty());
    }

    @Test void twoBlockAlternatingCornersHaveNoFalseJunctionsOrRoomTags() {
        int x=3,z=5;
        for(int i=0;i<10;i++){x(x,x+2,4,z);x+=2;z(z,z+2,x,4);z+=2;}
        var n=mode(true,25);assertEquals(1,n.paths().size());assertEquals(1,n.tunnels().size());
        assertEquals(0,markers(n,TunnelNetwork.Kind.JUNCTION));assertEquals(0,markers(n,TunnelNetwork.Kind.ROOM));
    }

    @Test void aThreeHighRoomDoesNotBecomeAPerimeterTunnel() {
        box(2,3,2,44,5,44);assertTrue(mode(true,12).paths().isEmpty());
    }

    @Test void strongerSmoothingStaysInsideTightZigZagAir() {
        int x=3,z=5;for(int i=0;i<10;i++){x(x,x+2,4,z);x+=2;z(z,z+2,x,4);z+=2;}
        var v=new Volume(-8,-8,-8,WIDTH,HEIGHT,WIDTH,0,0,blocks,null);
        for(var path:mode(true,12).paths()) {
            long[] p=path.points();
            for(int i=0;i<p.length-1;i++)for(int step=0;step<=12;step++) {
                long a=p[Math.max(0,i-1)],b=p[i],c=p[i+1],d=p[Math.min(p.length-1,i+2)];double t=step/12.0;
                double sx=TrailMath.smoothSpline(TrailMath.x(a),TrailMath.x(b),TrailMath.x(c),TrailMath.x(d),t,true)+.5;
                double sy=TrailMath.smoothSpline(TrailMath.y(a),TrailMath.y(b),TrailMath.y(c),TrailMath.y(d),t,true)+.5;
                double sz=TrailMath.smoothSpline(TrailMath.z(a),TrailMath.z(b),TrailMath.z(c),TrailMath.z(d),t,true)+.5;
                assertEquals(AIR,v.at((int)Math.floor(sx),(int)Math.floor(sy),(int)Math.floor(sz)));
            }
        }
    }

    @Test void repeatedLeftRightTurnsQualifyByTheirWalkedLength() {
        int bx=3,bz=5;
        for(int i=0;i<10;i++) {
            x(bx,bx+4,4,bz);bx+=4;int next=bz==5?9:5;z(Math.min(bz,next),Math.max(bz,next),bx,4);bz=next;
        }
        var n=mode(true,60);assertEquals(1,n.paths().size());assertEquals(1,n.tunnels().size());
        assertTrue(n.tunnels().getFirst().length()>=60);assertEquals(0,markers(n,TunnelNetwork.Kind.ROOM));
    }
}
