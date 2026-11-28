package com.scrimmy.client.sound;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Bounded, client-thread history. All scores, fades and aggregates are prepared on ticks. */
public final class SoundTracker {
    public record Weights(double weak, double mechanical, double unusual, double critical,
                          double repeat, double cluster, double sustained, long window, long sustainedAfter) {}
    public static final class Source {
        public final BlockPos pos;
        public final long firstHeard;
        public final boolean entity;
        public String soundId, category, blockId = "unloaded";
        public long lastHeard, hits;
        public boolean confirmed;
        public double score, opacity;
        public double x, y, z;
        private final ArrayDeque<Long> recent = new ArrayDeque<>();
        private Source(BlockPos pos, boolean entity, long now) {
            this.pos = pos.immutable(); this.entity = entity; firstHeard = lastHeard = now;
            x = pos.getX() + .5; y = pos.getY() + .5; z = pos.getZ() + .5;
        }
    }
    public static final class Chunk {
        public final int x, z;
        public int sources;
        public double score, opacity;
        private Chunk(int x, int z) { this.x = x; this.z = z; }
    }
    private final LinkedHashMap<BlockPos, Source> blocks = new LinkedHashMap<>();
    // Entity markers never overwrite block evidence or contribute to block/chunk confirmation.
    private final LinkedHashMap<BlockPos, Source> entities = new LinkedHashMap<>();
    private final Map<Long, Chunk> chunks = new HashMap<>();
    public Collection<Source> blocks() { return Collections.unmodifiableCollection(blocks.values()); }
    public Collection<Source> entities() { return Collections.unmodifiableCollection(entities.values()); }
    public Collection<Chunk> chunks() { return Collections.unmodifiableCollection(chunks.values()); }
    public boolean containsBlock(BlockPos pos) { return blocks.containsKey(pos); }
    public void clear() { blocks.clear(); entities.clear(); chunks.clear(); }
    public void clearEntities() { entities.clear(); }
    public Source hear(BlockPos pos, String sound, String category, boolean entity, long now, int limit) {
        var map = entity ? entities : blocks;
        Source source = map.get(pos);
        if (source == null) { source = new Source(pos, entity, now); map.put(source.pos, source); }
        source.soundId = sound; source.category = category; source.lastHeard = now; source.hits++;
        // Cap packet bursts as well as source count. The repeat bonus saturates at 128 hits.
        if (source.recent.size() == 128) source.recent.removeFirst();
        source.recent.addLast(now);
        trim(limit);
        return source;
    }
    public void trim(int limit) {
        while (blocks.size() + entities.size() > limit) {
            Source a = blocks.isEmpty() ? null : blocks.firstEntry().getValue();
            Source b = entities.isEmpty() ? null : entities.firstEntry().getValue();
            if (b == null || a != null && a.firstHeard <= b.firstHeard) blocks.pollFirstEntry();
            else entities.pollFirstEntry();
        }
    }
    public void tick(long now, long duration, long fade, int limit, Weights weights) {
        blocks.values().removeIf(s -> now - s.lastHeard >= duration);
        entities.values().removeIf(s -> now - s.lastHeard >= duration);
        trim(limit); chunks.clear();
        for (Source s : blocks.values()) {
            Chunk c = chunks.computeIfAbsent(chunkKey(s.pos),
                    k -> new Chunk(s.pos.getX() >> 4, s.pos.getZ() >> 4));
            c.sources++;
        }
        for (Source s : blocks.values()) {
            Chunk c = chunks.get(chunkKey(s.pos));
            score(s, now, duration, fade, weights, c.sources);
            c.score = Math.max(c.score, s.score); c.opacity = Math.max(c.opacity, s.opacity);
        }
        for (Source s : entities.values()) score(s, now, duration, fade, weights, 1);
    }
    private static long chunkKey(BlockPos pos) {
        return ((long)(pos.getX() >> 4) & 0xffffffffL) | ((long)(pos.getZ() >> 4) << 32);
    }
    private static void score(Source s, long now, long duration, long fade, Weights w, int cluster) {
        while (!s.recent.isEmpty() && now - s.recent.peekFirst() > w.window) s.recent.removeFirst();
        s.score = score(s.soundId, w, s.recent.size(), now - s.firstHeard, cluster);
        double t = fade <= 0 ? 1 : Math.clamp((duration - (now - s.lastHeard)) / (double)Math.min(fade, duration), 0, 1);
        s.opacity = t * t * (3 - 2 * t);
    }
    /** Shared scoring for live sources and persisted positional evidence. */
    public static double score(String id, Weights w, int recentHits, long age, int cluster) {
        double sustained = age < w.sustainedAfter ? 0
                : w.sustained * (1 + (age - w.sustainedAfter) / 60_000.0);
        return base(id, w) + Math.max(0, recentHits - 1) * w.repeat
                + (cluster - 1) * w.cluster + sustained;
    }
    public static Source historical(BlockPos pos, long firstSeen) { return new Source(pos, false, firstSeen); }
    public static double base(String id, Weights w) {
        String path = path(id);
        if (path.contains("explode") || path.contains("explosion") || path.contains("portal")
                || path.contains("beacon") || path.contains("bell.") || path.contains("wither")
                || path.contains("dragon")) return w.critical;
        if (path.contains("anvil") || path.contains("enchant") || path.contains("ender_chest")
                || path.contains("shulker")) return w.unusual;
        if (path.contains("piston") || path.contains("dispenser") || path.contains("dropper")) return w.mechanical;
        return w.weak;
    }
    public static int severity(double score, double medium, double red) {
        return score >= Math.max(medium, red) ? 2 : score >= medium ? 1 : 0;
    }
    public static String path(String id) { int colon = id.indexOf(':'); return colon < 0 ? id : id.substring(colon + 1); }
    public static boolean ignored(String id, String[] prefixes) {
        for (String prefix : prefixes) if (id.startsWith(prefix) || path(id).startsWith(prefix)) return true;
        return false;
    }
    /** Only positive evidence confirms a source; never search neighboring blocks to guess. */
    public static boolean matches(String sound, String block) {
        String s = path(sound), b = path(block);
        if (b.equals("air") || b.equals("cave_air") || b.equals("void_air") || b.equals("unloaded")) return false;
        if (s.startsWith("block.chest.")) return b.equals("chest") || b.equals("trapped_chest");
        if (s.startsWith("block.piston.")) return b.equals("piston") || b.equals("sticky_piston")
                || b.equals("piston_head") || b.equals("moving_piston");
        if (s.startsWith("block.wooden_door.")) return b.endsWith("_door") && !b.equals("iron_door") && !b.contains("copper");
        if (s.startsWith("block.wooden_trapdoor.")) return b.endsWith("_trapdoor") && !b.equals("iron_trapdoor") && !b.contains("copper");
        if (s.startsWith("block.wooden_button.")) return b.endsWith("_button") && !b.equals("stone_button") && !b.equals("polished_blackstone_button");
        if (s.startsWith("block.stone_button.")) return b.equals("stone_button") || b.equals("polished_blackstone_button");
        if (s.startsWith("block.shulker_box.")) return b.endsWith("shulker_box");
        if (s.startsWith("block.anvil.")) return b.endsWith("anvil");
        if (s.startsWith("block.dispenser.")) return b.equals("dispenser") || b.equals("dropper");
        if (s.startsWith("block.enchantment_table.")) return b.equals("enchanting_table");
        if (s.startsWith("block.portal.")) return b.equals("nether_portal");
        if (s.startsWith("music_disc.")) return b.equals("jukebox");
        if (!s.startsWith("block.")) return false;
        int dot = s.indexOf('.', 6);
        return dot > 6 && b.equals(s.substring(6, dot));
    }
}
