package com.scrimmy.client.camera;

import com.scrimmy.client.module.visual.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreecamInputTest {
    @Test void playerInputStaysEmptyEvenWhenAutoJumpRequestsAJump() throws Exception {
        Freecam module = new Freecam();
        var field = Freecam.class.getDeclaredField("cameraInput"); field.setAccessible(true);
        ClientInput input = (ClientInput)field.get(module);
        input.keyPresses = new Input(true, true, true, true, true, true, true);
        input.tick();
        assertSame(Input.EMPTY, input.keyPresses); assertSame(Vec2.ZERO, input.getMoveVector());
        input.makeJump();
        assertSame(Input.EMPTY, input.keyPresses);
        assertFalse(input.hasForwardImpulse());
    }
}
