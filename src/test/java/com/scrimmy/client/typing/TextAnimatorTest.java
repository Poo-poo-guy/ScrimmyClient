package com.scrimmy.client.typing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextAnimatorTest {
    private static final long START = 1_000_000_000L;
    private static final long SPEED = 200_000_000L;

    @Test void initialValueIsSettledAndUnchangedFramesReuseAllBuffers() {
        var state = new TextAnimator.State();
        state.update("existing name", START, SPEED);
        assertArrayEquals(new long[13], state.born);
        var born = state.born;
        var glyphs = state.glyphs;
        var duration = state.duration;
        state.update(new String("existing name"), START + 1, SPEED);
        assertSame(born, state.born);
        assertSame(glyphs, state.glyphs);
        assertSame(duration, state.duration);
    }

    @Test void middleInsertionAndReplacementKeepSuffixAnimationAttachedToItsCharacters() {
        var state = new TextAnimator.State();
        state.update("ab", START, SPEED);
        state.update("abZ", START + SPEED, SPEED);
        state.update("aXYbZ", START + SPEED + 60_000_000L, SPEED);
        assertEquals(START + SPEED, state.born[4]);
        assertEquals(0, state.born[3]);
        assertEquals(state.born[1], state.born[2]);
        state.update("aqbZ", START + SPEED + 90_000_000L, SPEED);
        assertEquals("aqbZ", state.previous);
        assertEquals(START + SPEED, state.born[3]);
        assertEquals(0, state.born[2]);
    }

    @Test void pasteIsOneFadeBlockAndADeletionDoesNotRespawnSurvivingText() {
        var state = new TextAnimator.State();
        state.update("left right", START, SPEED);
        state.update("left PASTED right", START + SPEED, SPEED);
        for (int i = 5; i < 12; i++) {
            assertTrue(state.pasted[i]);
            assertEquals(START + SPEED, state.born[i]);
            assertEquals(SPEED, state.duration[i]);
        }
        state.update("left right", START + SPEED + 1, SPEED);
        assertArrayEquals(new long[10], state.born);
        assertEquals(0, state.ghostCount); // Never-rendered characters must not create ghosts.
    }

    @Test void rapidTypingShortensExistingAnimationsAndNeverQueuesFutureStarts() {
        var state = new TextAnimator.State();
        state.update("", START, SPEED);
        state.update("a", START + SPEED, SPEED);
        state.update("ab", START + SPEED + 20_000_000L, SPEED);
        assertEquals(45_000_000L, state.duration[0]);
        assertEquals(45_000_000L, state.duration[1]);
        assertTrue(state.born[0] <= state.born[1]);
        assertEquals(START + SPEED + 20_000_000L, state.born[1]);
    }

    @Test void replacementOfEmojiDoesNotSplitTheSharedSurrogate() {
        var state = new TextAnimator.State();
        state.update("a😀z", START, SPEED);
        state.update("a😁z", START + SPEED, SPEED);
        assertEquals(START + SPEED, state.born[1]);
        assertEquals(state.born[1], state.born[2]);
        assertEquals(0, state.born[3]);
        state.update("az", START + SPEED + 1, SPEED);
        assertArrayEquals(new long[2], state.born);
    }
}
