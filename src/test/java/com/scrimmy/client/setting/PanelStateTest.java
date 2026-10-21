package com.scrimmy.client.setting;

import com.scrimmy.client.theme.PanelState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PanelStateTest {
    @Test void persistedPositionsCannotEscapeTheViewport() {
        PanelState state = new PanelState(-10, 8, false);
        assertEquals(0, state.x());
        assertEquals(1, state.y());
        assertFalse(state.open());
        assertEquals(0, new PanelState(Double.NaN, Double.POSITIVE_INFINITY, true).x());
    }
}
