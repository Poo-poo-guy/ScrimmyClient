package com.scrimmy.client.ghost;

import net.minecraft.world.level.Level;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LogoutGhostTrackerTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2), CAROL = new UUID(0, 3);
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
    private final LogoutGhostTracker tracker = new LogoutGhostTracker();
    private final Set<UUID> online = new HashSet<>();
    private final LogoutGhostTracker.TabList tab = online::contains;
    private final LogoutGhostTracker.Listener listener = new LogoutGhostTracker.Listener() {
        @Override public void created(LogoutGhostTracker.Ghost ghost) { created++; }
        @Override public void vanished(LogoutGhostTracker.Snapshot snapshot) { vanished++; }
    };
    private int created, vanished;
    private long now = 1_000_000_000L;
    private boolean outOfRange, notifyVanish;
    private int cap = 12;
    private double duration = 120;

    private void begin() { now += 50_000_000; tracker.beginTick(now); }
    private LogoutGhostTracker.Snapshot see(UUID uuid) {
        var snapshot = tracker.observe(uuid);
        snapshot.name = uuid.toString(); snapshot.x = 12; snapshot.y = 64; snapshot.z = -8;
        snapshot.yaw = 45; snapshot.width = .6f; snapshot.height = 1.8f; snapshot.dimension = OVERWORLD;
        return snapshot;
    }
    private void finish() { tracker.finishTick(tab, outOfRange, notifyVanish, duration, cap, listener); }
    private void frame(UUID... loaded) { begin(); for (UUID uuid : loaded) see(uuid); finish(); }

    @Test void logoutUsesLastSnapshotAndTabOrEntityReturnRemovesIt() {
        online.add(ALICE); frame(ALICE);
        online.remove(ALICE); frame();
        assertEquals(1, tracker.size()); assertEquals(1, created);
        var ghost = tracker.get(0);
        assertEquals(LogoutGhostTracker.Status.LOGGED_OUT, ghost.status);
        assertEquals(12, ghost.x); assertEquals(-8, ghost.z); assertEquals(45, ghost.yaw);
        assertEquals(.6f, ghost.width); assertEquals(1.8f, ghost.height); assertEquals(OVERWORLD, ghost.dimension);
        online.add(ALICE); frame(); assertEquals(0, tracker.size());
        frame(ALICE); online.remove(ALICE); frame(); assertEquals(1, tracker.size());
        frame(ALICE); assertEquals(0, tracker.size(), "Loaded return clears a ghost even if tab has not updated yet");
    }
    @Test void outOfRangeIsOptionalAndDoesNotDeleteItselfBecauseTabIsStillPresent() {
        online.add(ALICE); frame(ALICE); frame(); assertEquals(0, tracker.size());
        frame(ALICE); outOfRange = true; frame();
        assertEquals(1, tracker.size()); assertEquals(LogoutGhostTracker.Status.LAST_SEEN, tracker.get(0).status);
        for (int i = 0; i < 60; i++) frame();
        assertEquals(1, tracker.size()); assertEquals(1, created);
        frame(ALICE); assertEquals(0, tracker.size());
    }
    @Test void tabRemovalCanFollowEntityRemovalByAFewTicks() {
        online.add(ALICE); frame(ALICE);
        frame(); frame(); online.remove(ALICE); frame();
        assertEquals(1, tracker.size()); assertEquals(LogoutGhostTracker.Status.LOGGED_OUT, tracker.get(0).status);
    }
    @Test void anOldOutOfRangeSnapshotDoesNotBecomeAFreshLogoutLater() {
        online.add(ALICE); frame(ALICE);
        for (int i = 0; i <= LogoutGhostTracker.DEPARTURE_GRACE_TICKS; i++) frame();
        online.remove(ALICE); frame(); assertEquals(0, tracker.size());
    }
    @Test void loadedPlayersMissingFromTabNotifyOnceAfterGraceAndNeverLeaveAGhost() {
        notifyVanish = true; frame(ALICE);
        for (int i = 1; i < LogoutGhostTracker.VANISH_GRACE_TICKS; i++) frame(ALICE);
        assertEquals(0, vanished);
        frame(ALICE); assertEquals(1, vanished); assertEquals(0, tracker.size());
        for (int i = 0; i < 40; i++) frame(ALICE);
        assertEquals(1, vanished);
        frame(); assertEquals(0, tracker.size());
        online.add(ALICE); frame(ALICE); online.remove(ALICE);
        for (int i = 0; i <= LogoutGhostTracker.VANISH_GRACE_TICKS; i++) frame(ALICE);
        assertEquals(2, vanished, "A new tab disappearance may notify again");
    }
    @Test void deathsAndClearedSessionsDoNotCreateGhosts() {
        online.add(ALICE); begin(); see(ALICE).dead = true; finish();
        online.remove(ALICE); frame(); assertEquals(0, tracker.size());
        online.add(BOB); frame(BOB); tracker.clear();
        online.remove(BOB); frame(); assertEquals(0, tracker.size()); assertEquals(0, created);
    }
    @Test void oldestGhostsAreEvictedAndExpiryUsesElapsedSeconds() {
        cap = 2;
        frame(ALICE); frame(); frame(BOB); frame(); frame(CAROL); frame();
        assertEquals(2, tracker.size()); assertEquals(BOB, tracker.get(0).uuid); assertEquals(CAROL, tracker.get(1).uuid);
        now += 121_000_000_000L; frame(); assertEquals(0, tracker.size());
    }
    @Test void rangeGhostPromotesToLogoutAndTheCapCanBeReduced() {
        online.add(ALICE); outOfRange = true; frame(ALICE); frame();
        online.remove(ALICE); frame();
        assertEquals(1, tracker.size()); assertEquals(LogoutGhostTracker.Status.LOGGED_OUT, tracker.get(0).status);
        frame(BOB); frame(); assertEquals(2, tracker.size());
        cap = 1; frame(); assertEquals(1, tracker.size()); assertEquals(BOB, tracker.get(0).uuid);
    }
    @Test void snapshotsAreReusedAndTestGhostsDoNotAdvanceDetectionTicks() {
        online.add(ALICE); begin(); var first = see(ALICE); finish();
        begin(); assertSame(first, see(ALICE)); finish();
        var test = new LogoutGhostTracker.Snapshot(); test.uuid = CAROL; test.name = "Test"; test.dimension = OVERWORLD;
        tracker.test(test, now, cap, listener);
        outOfRange = true; frame();
        assertEquals(2, tracker.size()); assertEquals(LogoutGhostTracker.Status.LAST_SEEN, tracker.get(1).status);
    }
}
