package com.scrimmy.client.basefinding;

import static org.junit.jupiter.api.Assertions.*;
import com.scrimmy.client.basefinding.GeodeAnalysis.Geode;
import java.util.*;
import org.junit.jupiter.api.Test;

class BaseLikelihoodTest {
    private Geode geode(int cx, int cz, boolean grown) {
        return new Geode(cx * 16 + 8, -20, cz * 16 + 8,
                new GeodeScoring.Stats(10, 10, 40, grown ? 0 : 40, 0, 0, 0, grown ? 40 : 0, false));
    }
    private double score(List<BaseLikelihood.Chunk> grid, int x, int z) {
        return grid.stream().filter(c -> c.x() == x && c.z() == z).mapToDouble(BaseLikelihood.Chunk::score).findFirst().orElse(0);
    }
    @Test void exposedFacesUseWeightedStagesAndIgnoreBlockedFaces() {
        var stats = new GeodeScoring.Stats(8, 2, 5, 1, 1, 1, 1, 1, false);
        assertEquals(.5, BaseLikelihood.growth(stats), 1e-9);
        assertEquals(0, BaseLikelihood.growth(new GeodeScoring.Stats(1, 0, 0, 0, 0, 0, 0, 0, false)));
    }
    @Test void oldGeodeLogsWithoutBoundsStillReadAndScore() {
        var old = new com.google.gson.Gson().fromJson(
                "{\"x\":-40,\"y\":-20,\"z\":8,\"stats\":{\"budding\":1,\"exposedBudding\":1,\"exposed\":1,\"clusters\":1}}", Geode.class);
        assertEquals(-40, old.minX()); assertEquals(-39, old.maxX());
        assertEquals(1, BaseLikelihood.growth(old.stats()));
    }
    @Test void noPositiveEvidenceNeverProducesAResult() {
        assertTrue(BaseLikelihood.estimate(List.of(), 0, 0, 16).isEmpty());
        assertTrue(BaseLikelihood.estimate(List.of(geode(0, 0, false)), 0, 0, 16).isEmpty());
    }
    @Test void overlappingGrownGeodesPointBetweenThemAndHaveExactlyOnePrimary() {
        var grid = BaseLikelihood.estimate(List.of(geode(-3, 0, true), geode(3, 0, true)), 0, 0, 16);
        var primary = grid.stream().filter(BaseLikelihood.Chunk::primary).findFirst().orElseThrow();
        assertTrue(grid.getFirst().primary());
        assertTrue(Math.abs(primary.x()) <= 1);
        assertEquals(0, primary.z());
        assertEquals(100, primary.score(), 1e-9);
        assertEquals(1, grid.stream().filter(BaseLikelihood.Chunk::primary).count());
        assertTrue(grid.stream().allMatch(c -> c.score() >= 0 && c.score() <= 100));
    }
    @Test void ungrownGeodesShrinkTheSupportedSideInsteadOfAddingVotes() {
        var grown = geode(0, 0, true);
        var before = BaseLikelihood.estimate(List.of(grown), 0, 0, 16);
        var after = BaseLikelihood.estimate(List.of(grown, geode(5, 0, false), geode(5, 2, false)), 0, 0, 16);
        assertTrue(score(after, 5, 0) < score(before, 5, 0));
        assertTrue(score(after, -3, 0) > score(after, 3, 0));
    }
    @Test void duplicateBorderSnapshotsDoNotChangeScoresAndNegativeCoordinatesWork() {
        var a = geode(-10, -12, true); var b = geode(-7, -12, true);
        assertEquals(BaseLikelihood.estimate(List.of(a, b), -9, -12, 8),
                BaseLikelihood.estimate(List.of(a, b, a, b), -9, -12, 8));
        assertEquals(-10, (int)BaseLikelihood.key(-10, -12));
        assertEquals(-12, (int)(BaseLikelihood.key(-10, -12) >>> 32));
    }
    @Test void smoothingHasNoSingleChunkSpikeAndCandidateRangeIsBounded() {
        var grid = BaseLikelihood.estimate(List.of(geode(0, 0, true)), 0, 0, 4);
        assertTrue(score(grid, 1, 0) > 90);
        assertTrue(grid.stream().allMatch(c -> Math.abs(c.x()) <= 4 && Math.abs(c.z()) <= 4));
        assertTrue(grid.size() <= 81);
    }
}
