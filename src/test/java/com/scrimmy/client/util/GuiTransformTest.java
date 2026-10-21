package com.scrimmy.client.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiTransformTest {
    @Test void inputUsesOnlyTheAdditionalScale() {
        for (int minecraftScale : new int[]{1, 2, 3, 4}) {
            for (float clientScale : new float[]{0.75f, 1, 1.15f, 1.5f}) {
                GuiTransform transform = new GuiTransform(clientScale, minecraftScale);
                for (float local : new float[]{0, 12, 103.75f, 591})
                    assertEquals(local, transform.local(local * clientScale), 0.0001f);
            }
        }
    }

    @Test void textOriginsLandOnFramebufferPixels() {
        for (int minecraftScale : new int[]{1, 2, 3, 4}) {
            GuiTransform transform = new GuiTransform(1.15f, minecraftScale);
            float physical = transform.snap(43.3f) * 1.15f * minecraftScale;
            assertEquals(Math.round(physical), physical, 0.0001f);
        }
    }

    @Test void aSliderKeepsItsRangeWhenTheGuiScaleChangesDuringADrag() {
        GuiTransform.Track track = new GuiTransform(1, 2).track(200, 100);
        assertEquals(0.75, track.fraction(275), 0.0001);
        // A new transform for a resized/scaled frame cannot move the active drag's endpoints.
        assertNotEquals(track, new GuiTransform(1.5f, 2).track(200, 100));
        assertEquals(1, track.fraction(300));
        assertEquals(0, track.fraction(10));
    }
}
