package com.scrimmy.client.camera;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify required injection points against the project's real mapped game bytecode, without launching GL. */
class FreecamHooksTest {
    private ClassModel gameClass(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            return ClassFile.of().parse(stream.readAllBytes());
        }
    }
    private MethodModel method(ClassModel owner, String name, String descriptor) {
        return owner.methods().stream().filter(m -> m.methodName().equalsString(name) && m.methodType().equalsString(descriptor)).findFirst().orElseThrow();
    }
    private long calls(MethodModel method, String owner, String name, String descriptor) {
        return method.code().orElseThrow().elementStream().filter(element -> element instanceof InvokeInstruction call
            && call.owner().asInternalName().equals(owner) && call.name().equalsString(name) && call.type().equalsString(descriptor)).count();
    }
    @Test void cameraAndMouseHooksExistWithTheExpectedSignatures() throws Exception {
        var camera = gameClass("net/minecraft/client/Camera");
        method(camera, "setup", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V");
        method(camera, "setPosition", "(DDD)V"); method(camera, "setRotation", "(FF)V");
        assertTrue(camera.fields().stream().anyMatch(f -> f.fieldName().equalsString("detached") && f.fieldType().equalsString("Z")));
        var mouse = gameClass("net/minecraft/client/MouseHandler");
        method(mouse, "onScroll", "(JDD)V");
        assertEquals(1, calls(method(mouse, "turnPlayer", "(D)V"), "net/minecraft/client/player/LocalPlayer", "turn", "(DD)V"));
    }
    @Test void renderingHooksKeepVanillaWorldFeaturesAndUseSpectatorTerrainCulling() throws Exception {
        var renderer = gameClass("net/minecraft/client/renderer/GameRenderer");
        method(renderer, "bobView", "(Lcom/mojang/blaze3d/vertex/PoseStack;F)V");
        method(renderer, "bobHurt", "(Lcom/mojang/blaze3d/vertex/PoseStack;F)V");
        method(renderer, "pick", "(F)V");
        var render = method(renderer, "renderLevel", "(Lnet/minecraft/client/DeltaTracker;)V");
        assertEquals(1, calls(render, "net/minecraft/client/renderer/GameRenderer", "renderItemInHand", "(FZLorg/joml/Matrix4f;)V"));
        var level = gameClass("net/minecraft/client/renderer/LevelRenderer");
        var renderLevel = level.methods().stream().filter(m -> m.methodName().equalsString("renderLevel")).findFirst().orElseThrow();
        assertEquals(1, calls(renderLevel, "net/minecraft/client/renderer/LevelRenderer", "cullTerrain", "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V"));
    }
    @Test void interactionHooksCoverHeldMiningUsePickAndDrop() throws Exception {
        var minecraft = gameClass("net/minecraft/client/Minecraft");
        method(minecraft, "startAttack", "()Z"); method(minecraft, "continueAttack", "(Z)V");
        method(minecraft, "startUseItem", "()V"); method(minecraft, "pickBlock", "()V"); method(minecraft, "handleKeybinds", "()V");
    }
}
