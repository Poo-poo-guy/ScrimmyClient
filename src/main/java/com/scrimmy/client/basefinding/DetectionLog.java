package com.scrimmy.client.basefinding;

import com.google.gson.*;

import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Shared Basefinding log format. File work is serialized off the client thread. */
public final class DetectionLog implements AutoCloseable {
    public static final int LIMIT = 10000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Detection(
            int chunkX,
            int chunkZ,
            List<GeodeAnalysis.Geode> geodes,
            double score,
            long firstSeen,
            long lastSeen,
            String notes,
            boolean checked,
            boolean falsePositive) {
        public Detection {
            geodes = List.copyOf(geodes);
            notes = notes == null ? "" : notes;
        }

        public long key() {
            return key(chunkX, chunkZ);
        }

        public static long key(int x, int z) {
            return (x & 0xffffffffL) | ((long) z << 32);
        }

        public int clusters() {
            return geodes.stream().mapToInt(g -> g.stats().clusters()).sum();
        }

        public int buds() {
            return geodes.stream().mapToInt(g -> g.stats().filled() - g.stats().clusters()).sum();
        }

        public Detection annotation(String note, boolean done, boolean wrong) {
            return new Detection(
                    chunkX, chunkZ, geodes, score, firstSeen, lastSeen, note, done, wrong);
        }

        public String coordinates() {
            int y = geodes.isEmpty() ? 0 : geodes.getFirst().y();
            return (chunkX * 16 + 8) + " " + y + " " + (chunkZ * 16 + 8);
        }
    }

    private final Path directory;
    private final ScheduledExecutorService io =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "Scrimmy Basefinding log");
                        t.setDaemon(true);
                        return t;
                    });
    private ScheduledFuture<?> pending;
    private Runnable pendingWrite;

    public DetectionLog(Path directory) {
        this.directory = directory;
    }

    public Path file(String server, String dimension) {
        return directory.resolve(hash(server)).resolve(hash(dimension) + ".json");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public CompletableFuture<List<Detection>> load(String server, String dimension) {
        return CompletableFuture.supplyAsync(
                () -> read(file(server, dimension), server, dimension), io);
    }

    public synchronized void save(String server, String dimension, Collection<Detection> entries) {
        List<Detection> snapshot = List.copyOf(entries);
        if (pending != null) pending.cancel(false);
        pendingWrite = () -> write(file(server, dimension), server, dimension, snapshot);
        pending = io.schedule(pendingWrite, 500, TimeUnit.MILLISECONDS);
    }

    /** Queue the latest snapshot before changing servers/dimensions. */
    public synchronized void flush() {
        if (pending != null && pending.cancel(false) && pendingWrite != null)
            io.execute(pendingWrite);
        pending = null;
        pendingWrite = null;
    }

    public CompletableFuture<Path> export(
            String server, String dimension, Collection<Detection> entries) {
        List<Detection> snapshot = List.copyOf(entries);
        return CompletableFuture.supplyAsync(
                () -> {
                    Path target =
                            directory
                                    .resolve("exports")
                                    .resolve("basefinding-" + System.currentTimeMillis() + ".json");
                    write(target, server, dimension, snapshot);
                    return target;
                },
                io);
    }

    static List<Detection> read(Path path, String server, String dimension) {
        if (!Files.isRegularFile(path)) return List.of();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1
                    || !server.equals(root.get("server").getAsString())
                    || !dimension.equals(root.get("dimension").getAsString()))
                throw new IllegalArgumentException("Log context/version mismatch");
            List<Detection> result = new ArrayList<>();
            for (JsonElement entry : root.getAsJsonArray("detections")) {
                try {
                    Detection d = GSON.fromJson(entry, Detection.class);
                    if (!Double.isFinite(d.score)
                            || d.score < 0
                            || d.score > 100
                            || d.geodes.size() > 256
                            || Math.abs((long) d.chunkX) > 1875000
                            || Math.abs((long) d.chunkZ) > 1875000
                            || d.notes.length() > 4096) continue;
                    if (d.geodes.stream()
                            .anyMatch(
                                    g -> g == null || g.stats() == null || g.stats().exposed() < 0))
                        continue;
                    result.add(d);
                    if (result.size() == LIMIT) break;
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger("ScrimmyClient")
                            .warn("Skipping invalid Basefinding entry in {}", path);
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            LoggerFactory.getLogger("ScrimmyClient")
                    .error("Could not read Basefinding log {}", path, e);
            // Preserve the corrupt original before a later valid save replaces it.
            try {
                Files.copy(
                        path,
                        path.resolveSibling(
                                path.getFileName() + ".invalid-" + System.currentTimeMillis()));
            } catch (Exception backup) {
                LoggerFactory.getLogger("ScrimmyClient")
                        .error("Could not back up invalid log", backup);
            }
            return List.of();
        }
    }

    private static void write(Path path, String server, String dimension, List<Detection> entries) {
        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("server", server);
            root.addProperty("dimension", dimension);
            JsonArray array = new JsonArray();
            for (Detection d : entries) {
                JsonObject entry = GSON.toJsonTree(d).getAsJsonObject();
                entry.addProperty("source", "ChunkFinder");
                entry.addProperty("confidence", GeodeScoring.confidence(d.score));
                entry.addProperty("buds", d.buds());
                entry.addProperty("clusters", d.clusters());
                array.add(entry);
            }
            root.add("detections", array);
            temp = Files.createTempFile(path.getParent(), "basefinding-", ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("ScrimmyClient")
                    .error("Could not save Basefinding log {}", path, e);
            throw new CompletionException(e);
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
        }
    }

    @Override
    public void close() {
        flush();
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS))
                LoggerFactory.getLogger("ScrimmyClient").warn("Basefinding log is still flushing");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
