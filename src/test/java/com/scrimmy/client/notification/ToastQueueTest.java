package com.scrimmy.client.notification;

import com.scrimmy.client.animation.AnimationStyle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToastQueueTest {
    private static final AnimationStyle STYLE = AnimationStyle.get("Spring");
    private static void advance(ToastQueue queue, double from, double to) {
        for (double time = from; time < to; time += 1.0 / 120) queue.update(time, 1.0 / 120, true, 1, STYLE);
    }
    @Test void repeatedModuleUpdatesItsExistingToastAndReversesAnExitWithoutAJump() {
        ToastQueue queue = new ToastQueue();
        Object module = new Object();
        queue.show(module, "Module", true, 0, 1);
        var toast = queue.get(0);
        advance(queue, 0, 1.45);
        assertTrue(toast.closing());
        float reveal = toast.reveal.value();
        queue.show(module, "Module", false, 1.45, 1);
        assertSame(toast, queue.get(0)); assertEquals(1, queue.size());
        assertEquals(reveal, toast.reveal.value()); assertFalse(toast.closing());
        advance(queue, 1.45, 2.4);
        assertEquals(1, queue.size()); assertFalse(toast.enabled());
        assertEquals(0, toast.state.value(), .001);
        advance(queue, 2.4, 3.3); assertEquals(0, queue.size());
    }
    @Test void fifthToastWaitsForTheOldestToFadeAndNeverExceedsFourVisible() {
        ToastQueue queue = new ToastQueue();
        Object[] ids = {new Object(), new Object(), new Object(), new Object(), new Object()};
        for (int i = 0; i < 4; i++) queue.show(ids[i], "Module" + i, true, 0, 5);
        advance(queue, 0, .6);
        var oldest = queue.get(3);
        queue.show(ids[4], "Newest", true, .6, 5);
        assertEquals(4, queue.size()); assertEquals(1, queue.waiting());
        assertTrue(oldest.closing()); assertTrue(oldest.reveal.value() > .99);
        queue.show(ids[4], "Newest", false, .61, 5);
        assertEquals(1, queue.waiting());
        advance(queue, .61, 1.3);
        assertEquals(4, queue.size()); assertEquals(0, queue.waiting());
        assertEquals("Newest", queue.get(0).name()); assertFalse(queue.get(0).enabled());
    }
    @Test void aNewArrivalMovesOlderCardsSmoothlyAndPreservesTheirIdentity() {
        ToastQueue queue = new ToastQueue();
        Object first = new Object(); queue.show(first, "First", true, 0, 5);
        advance(queue, 0, .5); var old = queue.get(0);
        queue.show(new Object(), "Second", false, .5, 5);
        assertSame(old, queue.get(1)); assertEquals(0, old.offset.value());
        queue.update(.51, .01, true, 1, STYLE);
        assertTrue(old.offset.value() > 0 && old.offset.value() < 48);
        advance(queue, .51, 1.5); assertEquals(48, old.offset.value(), .1);
    }
    @Test void overloadAndDisabledAnimationsStayBoundedAndAllExpiredEntriesAreReleased() {
        ToastQueue queue = new ToastQueue();
        for (int i = 0; i < 100; i++) queue.show(new Object(), "Module", true, 0, 1);
        assertEquals(4, queue.size()); assertEquals(4, queue.waiting());
        for (int i = 0; i < 10; i++) {
            queue.update(i * 2, 2, false, 1, STYLE);
            assertTrue(queue.size() <= 4); assertTrue(queue.waiting() <= 4);
        }
        assertEquals(0, queue.size()); assertEquals(0, queue.waiting());
        queue.show(new Object(), "After idle", false, 30, 1);
        queue.update(30, .01, false, 1, STYLE);
        assertEquals(1, queue.get(0).reveal.value());
        queue.clear(); assertEquals(0, queue.size());
    }
    @Test void soundIsAnEmptyReplaceableHookCalledOnlyWhenACardAppears() {
        ToastQueue queue = new ToastQueue(); int[] calls = {0}; boolean[] state = {true};
        queue.sound(enabled -> { calls[0]++; state[0] = enabled; });
        Object id = new Object(); queue.show(id, "First", false, 0, 1);
        assertEquals(1, calls[0]); assertFalse(state[0]);
        queue.show(id, "First", true, .1, 1); assertEquals(1, calls[0]);
        queue.sound(enabled -> { throw new IllegalStateException("future sound failure"); });
        assertDoesNotThrow(() -> queue.show(new Object(), "Another", true, .2, 1));
        assertEquals(2, queue.size());
    }
    @Test void visibleStyleIsRetainedWhileNewAndQueuedToastsUseTheSelectedStyle() {
        ToastQueue queue = new ToastQueue(); Object first = new Object();
        queue.show(first, "Card", true, 0, 5, ToastStyle.CARD);
        advance(queue, 0, .5);
        var card = queue.get(0); float reveal = card.reveal.value();
        queue.show(first, "Card", false, .5, 5, ToastStyle.PILL);
        assertSame(card, queue.get(0)); assertEquals(ToastStyle.CARD, card.style());
        assertEquals(reveal, card.reveal.value());
        queue.show(new Object(), "Pill", true, .5, 5, ToastStyle.PILL);
        advance(queue, .5, 1.5);
        assertEquals(ToastStyle.PILL, queue.get(0).style());
        assertEquals(34, card.offset.value(), .1); // New pill height 26 + the shared gap 8.
        queue.show(new Object(), "Third", true, 1.5, 5, ToastStyle.CARD);
        queue.show(new Object(), "Fourth", true, 1.5, 5, ToastStyle.PILL);
        Object waiting = new Object(); queue.show(waiting, "Waiting", true, 1.5, 5, ToastStyle.CARD);
        queue.show(waiting, "Waiting", false, 1.51, 5, ToastStyle.PILL);
        advance(queue, 1.51, 2.5);
        assertEquals(4, queue.size()); assertEquals(0, queue.waiting());
        assertEquals(ToastStyle.PILL, queue.get(0).style()); assertFalse(queue.get(0).enabled());
    }
    @Test void pillNameAndStateUpdatesKeepTheSameSlotAndEaseFromTheCurrentState() {
        ToastQueue queue = new ToastQueue(); Object module = new Object();
        queue.show(module, "Short", true, 0, 1, ToastStyle.PILL);
        advance(queue, 0, .5); var pill = queue.get(0); long generation = pill.generation();
        queue.show(module, "A much longer module name", false, .5, 1, ToastStyle.PILL);
        assertSame(pill, queue.get(0)); assertEquals(generation, pill.generation());
        assertEquals("A much longer module name", pill.name()); assertEquals(1, pill.state.value());
        queue.update(.51, .01, true, 1, STYLE);
        assertTrue(pill.state.value() > 0 && pill.state.value() < 1);
        float midSwitch = pill.state.value();
        queue.show(module, pill.name(), true, .51, 1, ToastStyle.PILL);
        assertEquals(midSwitch, pill.state.value()); assertEquals(1, queue.size());
    }

}
