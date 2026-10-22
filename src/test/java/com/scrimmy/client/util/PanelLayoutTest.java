package com.scrimmy.client.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {
    @Test void allFivePanelsFitWithoutOverlapAtCommonScales() {
        for (int[] viewport : new int[][]{{320, 180}, {427, 240}, {640, 360}, {960, 540}, {1920, 1080}}) {
            for (double scale : new double[]{0.75, 1, 1.5}) {
                int w = (int)(viewport[0] / scale), h = (int)(viewport[1] / scale);
                PanelLayout layout = PanelLayout.fit(w, h, 200, 5, 4);
                for (int i = 0; i < 5; i++) {
                    assertTrue(layout.x(i) >= 4 && layout.x(i) + layout.width() <= w - 4);
                    assertTrue(layout.y(i) >= 4 && layout.y(i) + layout.cellHeight() <= h - 4);
                    for (int j = 0; j < i; j++)
                        assertTrue(layout.x(i) >= layout.x(j) + layout.width()
                            || layout.y(i) >= layout.y(j) + layout.cellHeight());
                }
            }
        }
    }
}
