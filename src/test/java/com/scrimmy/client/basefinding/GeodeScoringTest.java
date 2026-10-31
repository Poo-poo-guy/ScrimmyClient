package com.scrimmy.client.basefinding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GeodeScoringTest {
    private final GeodeScoring.Weights defaults =
            new GeodeScoring.Weights(60, 15, 15, 5, 5, .35, .0875);

    private GeodeScoring.Stats stats(
            int blocks, int exposed, int small, int medium, int large, int clusters, boolean made) {
        return new GeodeScoring.Stats(
                blocks,
                blocks,
                exposed,
                exposed - small - medium - large - clusters,
                small,
                medium,
                large,
                clusters,
                made);
    }

    @Test
    void naturalDistributionIsQuietAndMatureGeodesAreStrongEvidence() {
        var natural = stats(400, 400, 35, 35, 35, 35, false);
        assertEquals(0, GeodeScoring.score(natural, defaults, 0).score(), 1e-8);
        double mature = GeodeScoring.score(stats(80, 80, 0, 0, 0, 80, false), defaults, 0).score();
        assertTrue(mature > 80 && mature < 90);
        assertEquals("High", GeodeScoring.confidence(mature));
    }

    @Test
    void baselineIsPerBuddingBlockRatherThanPerFace() {
        // 400 blocks, 800 growable faces: natural expectations are still 140 placements/35
        // clusters.
        assertEquals(
                0,
                GeodeScoring.score(stats(400, 800, 35, 35, 35, 35, false), defaults, 0).score(),
                1e-8);
        assertTrue(
                GeodeScoring.score(stats(400, 800, 0, 0, 0, 70, false), defaults, 0).clusters()
                        > 0);
    }

    @Test
    void tinyAndUnexposedSamplesCannotBecomeHighConfidenceEvenWithBonuses() {
        assertEquals(0, GeodeScoring.score(stats(100, 0, 0, 0, 0, 0, true), defaults, 4).score());
        assertTrue(GeodeScoring.score(stats(1, 6, 0, 0, 0, 6, true), defaults, 2).score() < 25);
        assertTrue(
                GeodeScoring.score(stats(50, 100, 25, 25, 25, 25, true), defaults, 2).score() < 60);
    }

    @Test
    void bonusesAreBoundedAndWeightsRemainConfigurable() {
        var s = stats(100, 100, 0, 0, 0, 100, true);
        var a = GeodeScoring.score(s, defaults, 0);
        var b = GeodeScoring.score(s, defaults, 2);
        assertEquals(5, b.score() - a.score(), 1e-8);
        assertEquals(b.score(), GeodeScoring.score(s, defaults, 1000).score());
        assertEquals(
                0,
                GeodeScoring.score(s, new GeodeScoring.Weights(0, 0, 0, 0, 0, .35, .0875), 2)
                        .score());
        assertEquals(
                100,
                GeodeScoring.score(
                                s, new GeodeScoring.Weights(100, 100, 100, 50, 50, .35, .0875), 2)
                        .score());
        assertEquals("Low", GeodeScoring.confidence(59.99));
        assertEquals("Medium", GeodeScoring.confidence(60));
        assertEquals("High", GeodeScoring.confidence(80));
    }
}
