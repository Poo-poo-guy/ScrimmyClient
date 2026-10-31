package com.scrimmy.client.basefinding;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

class DetectionLogTest {
    @TempDir Path directory;

    private DetectionLog.Detection detection(int x) {
        return new DetectionLog.Detection(
                x,
                -5,
                List.of(
                        new GeodeAnalysis.Geode(
                                x * 16,
                                -20,
                                -72,
                                new GeodeScoring.Stats(30, 30, 30, 0, 0, 0, 0, 30, true))),
                88,
                100,
                200,
                "reviewed",
                true,
                false);
    }

    @Test
    void contextsAnnotationsAndExportsSurviveRestart() throws Exception {
        String dimension = "minecraft:overworld";
        try (DetectionLog log = new DetectionLog(directory)) {
            log.save("one:25565", dimension, List.of(detection(-123)));
            log.flush();
            log.save("two:25565", dimension, List.of(detection(456)));
            log.flush();
            assertEquals(List.of(detection(-123)), log.load("one:25565", dimension).get());
            assertEquals(List.of(detection(456)), log.load("two:25565", dimension).get());
            assertTrue(log.load("one:25565", "minecraft:the_nether").get().isEmpty());
            assertNotEquals(log.file("../../escape", dimension), directory.resolve("escape"));
            Path exported = log.export("one:25565", dimension, List.of(detection(-123))).get();
            JsonObject row =
                    JsonParser.parseString(Files.readString(exported))
                            .getAsJsonObject()
                            .getAsJsonArray("detections")
                            .get(0)
                            .getAsJsonObject();
            assertEquals("High", row.get("confidence").getAsString());
            assertEquals(30, row.get("clusters").getAsInt());
        }
        try (DetectionLog log = new DetectionLog(directory)) {
            assertEquals(List.of(detection(-123)), log.load("one:25565", dimension).get());
        }
    }

    @Test
    void debounceKeepsLatestSnapshotAndCorruptRowsDoNotDiscardValidNeighbors() throws Exception {
        try (DetectionLog log = new DetectionLog(directory)) {
            log.save("server", "overworld", List.of(detection(1)));
            log.save("server", "overworld", List.of(detection(2)));
            log.flush();
            assertEquals(List.of(detection(2)), log.load("server", "overworld").get());
            Path path = log.file("server", "overworld");
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            root.getAsJsonArray("detections").add(new JsonPrimitive("bad"));
            Files.writeString(path, root.toString());
            assertEquals(List.of(detection(2)), log.load("server", "overworld").get());
            assertTrue(log.load("different", "overworld").get().isEmpty());
            assertTrue(DetectionLog.read(path, "wrong server", "overworld").isEmpty());
        }
    }
}
