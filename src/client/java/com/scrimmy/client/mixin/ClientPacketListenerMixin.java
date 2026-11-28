package com.scrimmy.client.mixin;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.visual.HitParticles;
import com.scrimmy.client.module.basefinding.Basefinding;
import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.module.basefinding.SoundFinder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    // TAIL is reached only after PacketUtils has handed processing to the client thread.
    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void scrimmy$sound(ClientboundSoundPacket packet, CallbackInfo ci) {
        SoundFinder module = ScrimmyClient.MODULES.find(SoundFinder.class);
        if (module != null && module.enabled()) module.sound(packet);
    }
    @Inject(method = "handleSoundEntityEvent", at = @At("TAIL"))
    private void scrimmy$entitySound(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        SoundFinder module = ScrimmyClient.MODULES.find(SoundFinder.class);
        if (module != null && module.enabled()) module.entitySound(packet);
    }
    @Inject(method = "handleLevelEvent", at = @At("TAIL"))
    private void scrimmy$levelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        SoundFinder module = ScrimmyClient.MODULES.find(SoundFinder.class);
        if (module != null && module.enabled()) module.levelEvent(packet);
    }
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void scrimmy$chunkChanged(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        Basefinding module = ScrimmyClient.MODULES.find(Basefinding.class);
        if (module != null && module.scanner != null)
            module.scanner.changed(Minecraft.getInstance().level, packet.getX(), packet.getZ());
        DrillTrail drill = ScrimmyClient.MODULES.find(DrillTrail.class);
        if (drill != null && drill.scanner != null)
            drill.scanner.changed(Minecraft.getInstance().level, packet.getX(), packet.getZ());
        var v2 = ScrimmyClient.MODULES.find(com.scrimmy.client.module.basefinding.ChunkFinderV2.class);
        if (v2 != null && v2.enabled()) v2.changed(Minecraft.getInstance().level, packet.getX(), packet.getZ());
    }
    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void scrimmy$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        HitParticles module = ScrimmyClient.MODULES.find(HitParticles.class);
        if (module != null) module.damage(packet.entityId(), packet.sourceCauseId(), packet.sourceDirectId());
    }
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void scrimmy$death(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet.getEventId() != 3 || Minecraft.getInstance().level == null) return;
        HitParticles module = ScrimmyClient.MODULES.find(HitParticles.class);
        if (module != null) module.death(packet.getEntity(Minecraft.getInstance().level));
    }
}
