package com.scrimmy.client.module.visual;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.camera.FreecamMotion;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.setting.NumberSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

public final class Freecam extends Module {
    private static final double SECONDS_PER_TICK = .05;
    public final NumberSetting speed, sprintMultiplier;
    public final FreecamMotion camera = new FreecamMotion();
    private final ClientInput cameraInput = new CameraInput();
    private ClientInput previousInput;
    private LocalPlayer player;
    private ClientLevel world;

    public Freecam() {
        super("Freecam", "Fly the camera through walls; scroll to change speed", Category.RENDER);
        speed = add(new NumberSetting("speed", "Speed (blocks/s)", FreecamMotion.DEFAULT_SPEED, FreecamMotion.MIN_SPEED, FreecamMotion.MAX_SPEED, FreecamMotion.SPEED_STEP));
        speed.tooltip("Scroll up to speed up, down to slow down. Movement follows your normal key bindings.");
        sprintMultiplier = add(new NumberSetting("sprintMultiplier", "Sprint speed multiplier", 3, 1, 10, .5));
        sprintMultiplier.tooltip("Move this much faster while holding sprint.");
    }
    public static Freecam current() { return ScrimmyClient.MODULES.find(Freecam.class); }
    @Override public void initialize() {
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> enabled(false));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> enabled(false));
    }
    @Override protected void onEnable() { attach(Minecraft.getInstance()); }
    private void attach(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying() || mc.getCameraEntity() != mc.player) return;
        player = mc.player; world = mc.level; previousInput = player.input;
        var view = mc.gameRenderer.getMainCamera();
        if (view.isInitialized() && view.entity() == player) {
            var position = view.position();
            camera.reset(position.x, position.y, position.z, view.yRot(), view.xRot());
        } else {
            var eye = player.getEyePosition();
            camera.reset(eye.x, eye.y, eye.z, player.getYRot(), player.getXRot());
        }
        player.input = cameraInput;
        mc.levelRenderer.needsUpdate();
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
            if (player.isUsingItem()) mc.gameMode.releaseUsingItem(player);
        }
    }
    @Override protected void onDisable() {
        // Do not overwrite input installed by another mod while the camera was detached.
        if (player != null && player.input == cameraInput) player.input = previousInput;
        if (player != null) Minecraft.getInstance().levelRenderer.needsUpdate();
        previousInput = null; player = null; world = null;
    }
    public boolean active() {
        if (!enabled() || player == null) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player == player && mc.level == world && !player.isDeadOrDying() && mc.getCameraEntity() == player && player.input == cameraInput;
    }
    public boolean acceptsInput(Minecraft mc) {
        return active() && mc.screen == null && mc.getOverlay() == null && !mc.isPaused() && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed();
    }
    @Override public void onTick(Minecraft mc) {
        if (player == null) attach(mc);
        if (player == null) return;
        if (!active()) { enabled(false); return; }
        camera.beginTick();
        if (!acceptsInput(mc)) return;
        var keys = mc.options;
        int forward = (keys.keyUp.isDown() ? 1 : 0) - (keys.keyDown.isDown() ? 1 : 0);
        int right = (keys.keyRight.isDown() ? 1 : 0) - (keys.keyLeft.isDown() ? 1 : 0);
        int up = (keys.keyJump.isDown() ? 1 : 0) - (keys.keyShift.isDown() ? 1 : 0);
        double distance = speed.get() * SECONDS_PER_TICK * (keys.keySprint.isDown() ? sprintMultiplier.get() : 1);
        camera.move(forward, right, up, distance);
    }
    public void scroll(double horizontal, double vertical) {
        Minecraft mc = Minecraft.getInstance();
        double delta = vertical == 0 ? -horizontal : vertical;
        if (mc.options.discreteMouseScroll().get()) delta = Math.signum(delta);
        int steps = camera.scrollSteps(delta * mc.options.mouseWheelSensitivity().get());
        if (steps == 0) return;
        speed.set(FreecamMotion.scrollSpeed(speed.get(), steps));
        player.displayClientMessage(com.scrimmy.client.render.ClientFonts.component("Freecam speed: " + Math.round(speed.get() * 10) / 10.0 + " blocks/s"), true);
    }
    /** Even auto-jump must not turn a camera movement into a real player input. */
    private static final class CameraInput extends ClientInput {
        @Override public void tick() { keyPresses = Input.EMPTY; moveVector = Vec2.ZERO; }
        @Override public void makeJump() {}
    }
}
