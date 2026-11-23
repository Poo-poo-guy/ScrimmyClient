package com.scrimmy.client.sound;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;

/** Confirmation never loads a chunk or guesses a neighboring source. */
public final class SoundCapture {
    private SoundCapture() {}
    public record Sound(double x, double y, double z, String id, String category) {
        public net.minecraft.core.BlockPos pos() { return net.minecraft.core.BlockPos.containing(x,y,z); }
    }
    public static Sound read(net.minecraft.network.protocol.game.ClientboundSoundPacket packet) {
        return new Sound(packet.getX(),packet.getY(),packet.getZ(),packet.getSound().value().location().toString(),packet.getSource().getName());
    }
    public static String levelSound(ClientLevel world, net.minecraft.network.protocol.game.ClientboundLevelEventPacket packet) {
        if (packet.getType() == 1010) {
            return world.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.JUKEBOX_SONG).get(packet.getData())
                    .map(song -> song.value().soundEvent().value().location().toString()).orElse(null);
        }
        return SoundLevelEvents.sound(packet.getType(),packet.getData());
    }

    public static void confirm(ClientLevel world, SoundTracker.Source s) {
        s.confirmed = false;
        if (s.entity) { s.blockId = "entity marker"; return; }
        if (!world.hasChunk(s.pos.getX() >> 4, s.pos.getZ() >> 4)) { s.blockId = "unloaded"; return; }
        s.blockId = BuiltInRegistries.BLOCK.getKey(world.getBlockState(s.pos).getBlock()).toString();
        s.confirmed = SoundTracker.matches(s.soundId, s.blockId);
    }
}
