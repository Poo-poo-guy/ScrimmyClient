package com.scrimmy.client.basefinding;

import static com.scrimmy.client.basefinding.GeodeAnalysis.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class GeodeAnalysisTest {
    private final int width = 48, height = 32;

    private byte[] volume() {
        byte[] b = new byte[width * width * height];
        Arrays.fill(b, NATURAL);
        return b;
    }

    private void put(byte[] b, int x, int y, int z, int kind) {
        b[(y * width + z) * width + x] = (byte) kind;
    }

    private Volume input(byte[] b, int radius) {
        return new Volume(-16, -16, -16, width, height, width, b, 0, 0, radius, true, null);
    }

    @Test
    void sixFacesCountEachStageAndOnlyCorrectOrientation() {
        byte[] b = volume();
        put(b, 24, 16, 24, BUDDING);
        put(b, 24, 15, 24, AIR);
        put(b, 24, 17, 24, WATER);
        put(b, 24, 16, 23, STAGE + 2 * 4);
        put(b, 24, 16, 25, STAGE + 3 * 4 + 1);
        put(b, 23, 16, 24, STAGE + 4 * 4 + 2);
        put(b, 25, 16, 24, STAGE + 5 * 4 + 3);
        var results = analyze(input(b, 4));
        assertEquals(1, results.size());
        var s = results.getFirst().stats();
        assertEquals(6, s.exposed());
        assertEquals(2, s.empty());
        assertEquals(1, s.small());
        assertEquals(1, s.medium());
        assertEquals(1, s.large());
        assertEquals(1, s.clusters());
        assertEquals(1, s.exposedBudding());
        put(b, 25, 16, 24, STAGE + 4 * 4 + 3);
        assertEquals(5, analyze(input(b, 4)).getFirst().stats().exposed());
    }

    @Test
    void calciteConnectsOneGeodeAcrossChunkBordersAndKeepsSeparateShellsApart() {
        byte[] b = volume();
        put(b, 31, 16, 24, BUDDING);
        put(b, 32, 16, 24, SHELL);
        put(b, 33, 16, 24, BUDDING);
        put(b, 31, 17, 24, AIR);
        put(b, 33, 17, 24, STAGE + 4 + 3);
        put(b, 20, 16, 20, BUDDING);
        put(b, 20, 17, 20, AIR);
        var geodes = analyze(input(b, 4));
        assertEquals(2, geodes.size());
        var connected =
                geodes.stream().filter(g -> g.stats().budding() == 2).findFirst().orElseThrow();
        assertEquals(2, connected.stats().exposed());
        assertEquals(1, connected.stats().clusters());
        var neighbor =
                analyze(new Volume(-16, -16, -16, width, height, width, b, 1, 0, 4, true, null));
        assertEquals(1, neighbor.size());
        assertEquals(connected.id(), neighbor.getFirst().id());
    }

    @Test
    void unloadedBoundariesAndUnexposedGeodesAreNotScored() {
        byte[] b = volume();
        put(b, 24, 16, 24, BUDDING);
        assertTrue(analyze(input(b, 4)).isEmpty());
        put(b, 24, 17, 24, AIR);
        put(b, 25, 16, 24, SHELL);
        put(b, 26, 16, 24, UNKNOWN);
        assertTrue(analyze(input(b, 4)).isEmpty());
        put(b, 26, 16, 24, NATURAL);
        assertEquals(1, analyze(input(b, 4)).size());
    }

    @Test
    void evidenceUsesConfiguredSphericalRadius() {
        byte[] b = volume();
        put(b, 24, 16, 24, BUDDING);
        put(b, 24, 17, 24, AIR);
        put(b, 28, 16, 24, MAN_MADE);
        assertFalse(analyze(input(b, 3)).getFirst().stats().manMade());
        assertTrue(analyze(input(b, 4)).getFirst().stats().manMade());
        assertFalse(
                analyze(new Volume(-16, -16, -16, width, height, width, b, 0, 0, 4, false, null))
                        .getFirst()
                        .stats()
                        .manMade());
    }

    @Test
    void paletteSeedsRestrictTheSearchWhileConnectivityStillIncludesNeighborBudding() {
        byte[] b = volume();
        put(b, 24, 16, 24, BUDDING);
        put(b, 25, 16, 24, SHELL);
        put(b, 26, 16, 24, BUDDING);
        put(b, 24, 17, 24, AIR);
        put(b, 26, 17, 24, AIR);
        put(b, 20, 16, 20, BUDDING);
        put(b, 20, 17, 20, AIR);
        int seed = (16 * width + 24) * width + 24;
        var geodes =
                analyze(
                        new Volume(
                                -16,
                                -16,
                                -16,
                                width,
                                height,
                                width,
                                b,
                                0,
                                0,
                                4,
                                true,
                                new int[] {seed}));
        assertEquals(1, geodes.size());
        assertEquals(2, geodes.getFirst().stats().budding());
        assertTrue(
                analyze(
                                new Volume(
                                        -16,
                                        -16,
                                        -16,
                                        width,
                                        height,
                                        width,
                                        b,
                                        0,
                                        0,
                                        4,
                                        true,
                                        new int[0]))
                        .isEmpty());
    }
}
