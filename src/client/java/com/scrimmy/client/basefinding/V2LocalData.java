package com.scrimmy.client.basefinding;

import com.scrimmy.client.basefinding.HumanActivityAnalysis.Ranked;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local-only marks and CSV exports. All disk work is serialized off the client thread. */
public final class V2LocalData implements AutoCloseable {
    private final Path root;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Scrimmy V2 local files"); t.setDaemon(true); return t;
    });
    public V2LocalData(Path gameDirectory) { root = gameDirectory.resolve("scrimmy/chunkfinder-v2"); }
    public static String scope(String server, String dimension) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((server+"\n"+dimension).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public CompletableFuture<Map<Long, String>> load(String scope) {
        return CompletableFuture.supplyAsync(() -> readMarks(scope), io);
    }
    private Map<Long, String> readMarks(String scope) {
        Path file = root.resolve(scope+"-marks.csv");
        if (!Files.exists(file)) return Map.of();
        try {
            if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Marks file exceeds 4 MiB");
            Map<Long,String> marks = new HashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.equals("chunk_x,chunk_z,label") || line.isBlank()) continue;
                String[] cells = line.split(",", -1);
                if (cells.length != 3 || !Set.of("base","not base").contains(cells[2])) throw new IOException("Invalid mark row");
                marks.put(com.scrimmy.client.trail.TrailMath.chunk(Integer.parseInt(cells[0]),Integer.parseInt(cells[1])),cells[2]);
            }
            return Map.copyOf(marks);
        } catch (IOException | NumberFormatException e) { throw new CompletionException(e); }
    }
    /** Preserve marks even if the user labels a chunk before the initial read finishes. */
    public CompletableFuture<Path> updateMark(String scope, long key, String label) {
        return CompletableFuture.supplyAsync(() -> {
            Map<Long,String> merged = new HashMap<>(readMarks(scope)); merged.put(key,label);
            return writeMarks(scope,merged);
        },io);
    }
    public CompletableFuture<Path> save(String scope, Map<Long,String> marks) {
        Map<Long,String> snapshot = Map.copyOf(marks);
        return CompletableFuture.supplyAsync(() -> writeMarks(scope,snapshot),io);
    }
    private Path writeMarks(String scope, Map<Long,String> snapshot) {
        try {
            Files.createDirectories(root);
            Path file = root.resolve(scope+"-marks.csv"), temp = Files.createTempFile(root, "marks-", ".tmp");
            try {
                try (var out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    out.write("chunk_x,chunk_z,label\n");
                    for (long key : snapshot.keySet().stream().sorted().toList())
                        out.write((int)key+","+(int)(key>>>32)+","+snapshot.get(key)+"\n");
                }
                try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            return file;
        } catch (IOException e) { throw new CompletionException(e); }
    }
    public CompletableFuture<Path> export(String scope, List<Ranked> rows, Map<Long,String> marks) {
        List<Ranked> snapshot = List.copyOf(rows); Map<Long,String> labels = Map.copyOf(marks);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path folder = root.resolve("exports"); Files.createDirectories(folder);
                Path file = folder.resolve(scope+"-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".csv");
                try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    out.write("chunk_x,chunk_z,exposed_rarity,tunnel_samples,leaked_blocks,leaked_non_natural,leaked_rarity,amethyst_growth,flattened,terraces,pillars,casts,raw_score,smoothed_score,mark,fragment_size_non_natural\n");
                    for (Ranked row : snapshot) {
                        var f = row.features();
                        String fragments = String.join(";", f.fragments().stream().map(p -> p.size()+":"+p.nonNatural()).toList());
                        out.write(String.format(Locale.ROOT,"%d,%d,%.5f,%d,%d,%d,%.5f,%.5f,%d,%d,%d,%d,%.5f,%.5f,%s,%s%n",
                                row.x(),row.z(),f.exposed(),f.tunnels(),f.leakedBlocks(),f.leakedNonNatural(),f.leakedRarity(),f.growth(),
                                f.flattened(),f.terraces(),f.pillars(),f.casts(),row.rawScore(),row.score(),labels.getOrDefault(row.key(),""),fragments));
                    }
                }
                return file;
            } catch (IOException e) { throw new CompletionException(e); }
        },io);
    }
    @Override public void close() {
        io.shutdown();
        try { io.awaitTermination(5,TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
