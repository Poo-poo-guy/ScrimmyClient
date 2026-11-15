package com.scrimmy.client.ghost;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tick-only departure detection, independent of rendering and notifications. */
public final class LogoutGhostTracker {
    public static final int CAPACITY = 50, VANISH_GRACE_TICKS = 20;
    // Entity removal and tab removal can arrive on different ticks. Keep a short pending snapshot.
    public static final int DEPARTURE_GRACE_TICKS = 40;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    public enum Status {
        LOGGED_OUT("LOGGED OUT"), LAST_SEEN("LAST SEEN"), TEST("TEST");
        public final String label;
        Status(String label) { this.label = label; }
    }
    public interface TabList { boolean contains(UUID uuid); }
    public interface Listener {
        void created(Ghost ghost);
        void vanished(Snapshot snapshot);
    }
    public static class Snapshot {
        public UUID uuid;
        public String name;
        public PlayerSkin skin;
        public ResourceKey<Level> dimension;
        public double x, y, z;
        public float yaw, width, height;
        public boolean dead;
        public long seenTick, seenNanos;
        private long missingTabSince = -1;
        private boolean vanished, vanishNotified;
        void copy(Snapshot other) {
            uuid = other.uuid; name = other.name; skin = other.skin; dimension = other.dimension;
            x = other.x; y = other.y; z = other.z; yaw = other.yaw; width = other.width; height = other.height;
            dead = other.dead; seenTick = other.seenTick; seenNanos = other.seenNanos;
        }
    }
    public static final class Ghost extends Snapshot {
        public final int slot;
        public Status status;
        public long createdNanos;
        private boolean tabPresent;
        private Ghost(int slot) { this.slot = slot; }
        public double age(long now) { return Math.max(0, (now - createdNanos) / NANOS_PER_SECOND); }
    }
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Ghost[] pool = new Ghost[CAPACITY], ghosts = new Ghost[CAPACITY];
    private int count;
    private long tick, now;

    public LogoutGhostTracker() { for (int i = 0; i < pool.length; i++) pool[i] = new Ghost(i); }
    public int size() { return count; }
    public Ghost get(int index) { return ghosts[index]; }
    public void beginTick(long now) { this.now = now; tick++; }
    public Snapshot observe(UUID uuid) {
        Snapshot snapshot = snapshots.get(uuid);
        if (snapshot == null) { snapshot = new Snapshot(); snapshot.uuid = uuid; snapshots.put(uuid, snapshot); }
        snapshot.seenTick = tick; snapshot.seenNanos = now;
        for (int i = count - 1; i >= 0; i--) if (ghosts[i].uuid.equals(uuid)) remove(i);
        return snapshot;
    }
    public void finishTick(TabList tab, boolean outOfRange, boolean notifyVanish, double duration, int cap, Listener listener) {
        for (int i = count - 1; i >= 0; i--) {
            Ghost ghost = ghosts[i];
            boolean present = tab.contains(ghost.uuid);
            // LAST SEEN starts with tab present; only a later return removes it.
            if (ghost.age(now) >= duration || (present && !ghost.tabPresent && ghost.status != Status.TEST)) remove(i);
            else ghost.tabPresent = present;
        }
        var iterator = snapshots.values().iterator();
        while (iterator.hasNext()) {
            Snapshot snapshot = iterator.next();
            boolean present = tab.contains(snapshot.uuid);
            if (snapshot.seenTick == tick) {
                if (present) { snapshot.missingTabSince = -1; snapshot.vanished = snapshot.vanishNotified = false; }
                else {
                    if (snapshot.missingTabSince < 0) snapshot.missingTabSince = tick;
                    if (tick - snapshot.missingTabSince >= VANISH_GRACE_TICKS) {
                        snapshot.vanished = true;
                        if (notifyVanish && !snapshot.vanishNotified && !snapshot.dead) {
                            snapshot.vanishNotified = true; listener.vanished(snapshot);
                        }
                    }
                }
                continue;
            }
            long missingTicks = tick - snapshot.seenTick;
            if (!snapshot.dead && !snapshot.vanished) {
                if (!present && missingTicks <= DEPARTURE_GRACE_TICKS) {
                    create(snapshot, Status.LOGGED_OUT, false, cap, listener); iterator.remove(); continue;
                }
                if (present && outOfRange && missingTicks == 1) create(snapshot, Status.LAST_SEEN, true, cap, listener);
            }
            if (missingTicks > DEPARTURE_GRACE_TICKS) iterator.remove();
        }
        trim(cap);
    }
    public Ghost test(Snapshot snapshot, long now, int cap, Listener listener) { this.now = now; return create(snapshot, Status.TEST, false, cap, listener); }
    private Ghost create(Snapshot snapshot, Status status, boolean tabPresent, int cap, Listener listener) {
        for (int i = count - 1; i >= 0; i--) if (ghosts[i].uuid.equals(snapshot.uuid)) remove(i);
        while (count >= Math.max(1, Math.min(CAPACITY, cap))) remove(0);
        Ghost ghost = null;
        for (Ghost candidate : pool) {
            boolean used = false;
            for (int i = 0; i < count; i++) if (ghosts[i] == candidate) { used = true; break; }
            if (!used) { ghost = candidate; break; }
        }
        ghost.copy(snapshot); ghost.status = status; ghost.createdNanos = now; ghost.tabPresent = tabPresent;
        ghosts[count++] = ghost;
        listener.created(ghost);
        return ghost;
    }
    private void trim(int cap) { while (count > Math.max(1, Math.min(CAPACITY, cap))) remove(0); }
    private void remove(int index) {
        Ghost removed = ghosts[index];
        System.arraycopy(ghosts, index + 1, ghosts, index, count - index - 1);
        ghosts[--count] = null;
        removed.skin = null;
    }
    public void clear() {
        snapshots.clear();
        while (count > 0) remove(count - 1);
        tick = 0;
    }
}
