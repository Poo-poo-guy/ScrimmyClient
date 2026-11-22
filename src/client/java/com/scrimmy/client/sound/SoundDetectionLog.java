package com.scrimmy.client.sound;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Optional local JSONL evidence log. A bounded writer queue never blocks the client thread. */
public final class SoundDetectionLog implements AutoCloseable {
    private final Map<BlockPos, Integer> logged = new HashMap<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> { Thread t = new Thread(r, "Scrimmy sound log"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardPolicy());
    public void retain(SoundTracker tracker) {
        logged.keySet().removeIf(pos -> !tracker.containsBlock(pos));
    }
    public void record(Minecraft mc, SoundTracker.Source s, int severity) {
        Integer previous = logged.get(s.pos);
        if (previous != null && severity <= previous) return;
        logged.put(s.pos, severity);
        JsonObject row = new JsonObject();
        String server = mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip;
        if (mc.getSingleplayerServer() != null) server = "singleplayer:" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        row.addProperty("server", server); row.addProperty("dimension", mc.level.dimension().identifier().toString());
        row.addProperty("x", s.pos.getX()); row.addProperty("y", s.pos.getY()); row.addProperty("z", s.pos.getZ());
        row.addProperty("sound", s.soundId); row.addProperty("category", s.category); row.addProperty("block", s.blockId);
        row.addProperty("firstHeard", s.firstHeard); row.addProperty("lastHeard", s.lastHeard); row.addProperty("hits", s.hits);
        row.addProperty("confirmed", s.confirmed); row.addProperty("score", s.score); row.addProperty("severity", severity);
        String line = row + System.lineSeparator();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("scrimmy/soundfinder/detections.jsonl");
        writer.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                // Retain the previous 4 MiB, avoiding an indefinitely growing diagnostics file.
                if (Files.exists(path) && Files.size(path) > 4 * 1024 * 1024)
                    Files.move(path, path.resolveSibling("detections.previous.jsonl"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) { LoggerFactory.getLogger("ScrimmyClient").error("Could not save sound detection", error); }
        });
    }
    public void clear() { logged.clear(); }
    @Override public void close() {
        writer.shutdown();
        try { writer.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
