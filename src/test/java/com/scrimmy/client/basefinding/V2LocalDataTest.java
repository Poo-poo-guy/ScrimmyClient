package com.scrimmy.client.basefinding;

import com.scrimmy.client.basefinding.HumanActivityAnalysis.*;
import com.scrimmy.client.trail.TrailMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class V2LocalDataTest {
    @TempDir Path game;
    @Test void marksRoundTripWithoutLeakingBetweenServersWorldsAndDimensions() throws Exception {
        String a = V2LocalData.scope("server-a","overworld"), b = V2LocalData.scope("server-b","overworld"), c = V2LocalData.scope("server-a","nether");
        assertNotEquals(a,b); assertNotEquals(a,c); assertFalse(a.contains("server-a"));
        long key = TrailMath.chunk(-31,42);
        try (V2LocalData files = new V2LocalData(game)) {
            assertTrue(files.load(a).get().isEmpty());
            Path path = files.save(a,Map.of(key,"base")).get(); assertTrue(path.startsWith(game));
            assertEquals(Map.of(key,"base"),files.load(a).get()); assertTrue(files.load(b).get().isEmpty()); assertTrue(files.load(c).get().isEmpty());
            files.save(a,Map.of(key,"not base")).get();
        }
        try (V2LocalData files = new V2LocalData(game)) { assertEquals(Map.of(key,"not base"),files.load(a).get()); }
    }
    @Test void exportContainsEveryRawFeatureAndExactLabelsWithLocaleIndependentNumbers() throws Exception {
        Features f = new Features(12.5,7,6,2,10,.75,3,4,5,6,List.of(new Fragment(4,1),new Fragment(2,1)));
        Ranked row = new Ranked(TrailMath.chunk(-4,8),f,61.2,58.4);
        Locale old = Locale.getDefault(); Locale.setDefault(Locale.GERMANY);
        try (V2LocalData files = new V2LocalData(game)) {
            Path path = files.export(V2LocalData.scope("server","dimension"),List.of(row),Map.of(row.key(),"not base")).get();
            List<String> lines = Files.readAllLines(path); String[] header = lines.get(0).split(","), cells = lines.get(1).split(",",-1);
            assertEquals(header.length,cells.length); assertEquals(16,cells.length);
            assertEquals("-4",cells[0]); assertEquals("8",cells[1]); assertEquals("12.50000",cells[2]); assertEquals("7",cells[3]);
            assertEquals("6",cells[4]); assertEquals("2",cells[5]); assertEquals("10.00000",cells[6]); assertEquals("0.75000",cells[7]);
            assertEquals(List.of("3","4","5","6"),Arrays.asList(cells).subList(8,12));
            assertEquals("61.20000",cells[12]); assertEquals("58.40000",cells[13]); assertEquals("not base",cells[14]); assertEquals("4:1;2:1",cells[15]);
            assertTrue(path.startsWith(game.resolve("scrimmy/chunkfinder-v2/exports")));
        } finally { Locale.setDefault(old); }
    }
    @Test void queuedLabelsMergeAndFlushWhenClosingBeforeReadsFinish() throws Exception {
        String scope = V2LocalData.scope("server","dimension");
        V2LocalData files = new V2LocalData(game);
        files.save(scope,Map.of(1L,"base")).get();
        files.load(scope);
        files.updateMark(scope,2L,"not base"); files.updateMark(scope,3L,"base"); files.close();
        try (V2LocalData reopened = new V2LocalData(game)) {
            assertEquals(Map.of(1L,"base",2L,"not base",3L,"base"),reopened.load(scope).get());
        }
    }
    @Test void corruptMarksAreReportedAndNeverOverwrittenByAnEarlyLabel() throws Exception {
        String scope = V2LocalData.scope("server","dimension");
        Path folder = game.resolve("scrimmy/chunkfinder-v2"); Files.createDirectories(folder);
        Path file = folder.resolve(scope+"-marks.csv"); Files.writeString(file,"broken row\n");
        try (V2LocalData files = new V2LocalData(game)) {
            assertThrows(java.util.concurrent.ExecutionException.class,()->files.load(scope).get());
            assertThrows(java.util.concurrent.ExecutionException.class,()->files.updateMark(scope,1L,"base").get());
            assertEquals("broken row\n",Files.readString(file));
        }
    }
}
