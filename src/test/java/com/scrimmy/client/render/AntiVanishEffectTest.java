package com.scrimmy.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class AntiVanishEffectTest {
    @Test void textFadesFollowTheRequestedTimeline() {
        assertEquals(0, title(1.25));
        assertEquals(.5, title(1.5), 1e-9);
        assertEquals(1, title(1.75));
        assertEquals(1, title(3.75));
        assertEquals(.5, title(4.125), 1e-9);
        assertEquals(0, title(4.5));
        assertEquals(0, note(4.5));
        assertEquals(.5, note(4.75), 1e-9);
        assertEquals(1, note(5));
        assertEquals(1, note(7.5));
        assertEquals(.5, note(8), 1e-9);
        assertEquals(0, note(8.5));
        assertEquals(0, note(100));
    }
    private double title(double age) {
        return AntiVanishEffect.opacity(age, AntiVanishEffect.TITLE_START, AntiVanishEffect.TITLE_FADE_IN, AntiVanishEffect.TITLE_HOLD, AntiVanishEffect.TITLE_FADE_OUT);
    }
    private double note(double age) {
        return AntiVanishEffect.opacity(age, AntiVanishEffect.NOTE_START, AntiVanishEffect.NOTE_FADE_IN, AntiVanishEffect.NOTE_HOLD, AntiVanishEffect.NOTE_FADE_OUT);
    }
    @Test void allSixFacesWindOutwardsAndUseTheVanillaSkinAtlas() {
        ByteBuffer bytes = ByteBuffer.allocate(24 * 24).order(ByteOrder.nativeOrder());
        AntiVanishHeadRenderer.cube(bytes, .5f, 0);
        int[][] atlas = {{8,8}, {16,8}, {24,8}, {0,8}, {8,0}, {16,0}};
        for (int face = 0; face < 6; face++) {
            int offset = face * 4 * 24;
            Vector3f a = position(bytes, offset), b = position(bytes, offset + 24), c = position(bytes, offset + 48);
            Vector3f outward = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            assertTrue(outward.dot(a) > 0, "Face " + face + " must be counterclockwise from outside");
            assertEquals(atlas[face][0] / 64f, bytes.getFloat(offset + 12));
            assertEquals((atlas[face][1] + (face == 5 ? 8 : 0)) / 64f, bytes.getFloat(offset + 16));
        }
        // Front top-left is screen-left and above center; the face is upright and unmirrored.
        assertEquals(new Vector3f(-.5f, .5f, .5f), position(bytes, 0));
        assertEquals(new Vector3f(.5f, -.5f, .5f), position(bytes, 48));
        ByteBuffer hat = ByteBuffer.allocate(bytes.capacity()).order(ByteOrder.nativeOrder());
        AntiVanishHeadRenderer.cube(hat, .5f * AntiVanishEffect.HEAD_HAT_SCALE, 32);
        for (int vertex = 0; vertex < 24; vertex++) {
            int offset = vertex * 24;
            assertEquals(position(bytes, offset).mul(1.125f), position(hat, offset));
            assertEquals(bytes.getFloat(offset + 12) + .5f, hat.getFloat(offset + 12));
            assertEquals(bytes.getFloat(offset + 16), hat.getFloat(offset + 16));
        }
    }
    @Test void spinEndsFacingTheCameraAndTheRushStaysInFrontOfTheNearPlane() {
        var start = AntiVanishEffect.headMotion(0, 1, 1.5f);
        assertEquals(-Math.PI * 3, start.yaw(), 1e-6);
        float previousYaw = start.yaw();
        for (int frame = 0; frame <= 1000; frame++) {
            double progress = frame / 1000.0;
            for (float size : new float[]{.25f, 1, 2}) {
                var motion = AntiVanishEffect.headMotion(progress, size, 1.5f);
                Matrix4f view = new Matrix4f().translate(0, 0, -motion.distance()).rotateZ(motion.roll())
                    .rotateX(motion.pitch()).rotateY(motion.yaw()).scale(motion.size() * AntiVanishEffect.HEAD_HAT_SCALE / 2);
                for (float[] face : AntiVanishHeadRenderer.FACE_CORNERS) for (int v = 0; v < 4; v++) {
                    Vector3f corner = view.transformPosition(new Vector3f(face[v*3], face[v*3+1], face[v*3+2]));
                    assertTrue(corner.z < -AntiVanishEffect.HEAD_NEAR_PLANE, "Hat must not cross the near plane");
                }
            }
            var motion = AntiVanishEffect.headMotion(progress, 1, 1.5f);
            assertTrue(motion.yaw() >= previousYaw, "Spin direction must remain consistent");
            previousYaw = motion.yaw();
            if (progress >= .85) assertEquals(0, motion.yaw(), 1e-6, "Front must face the camera throughout the rush");
        }
        var end = AntiVanishEffect.headMotion(1, 1, 1.5f);
        assertEquals(0, end.pitch()); assertEquals(0, end.roll());
        assertTrue(end.distance() < start.distance());
        assertTrue(end.size() > start.size());
    }
    private Vector3f position(ByteBuffer bytes, int offset) {
        return new Vector3f(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
    }
}
