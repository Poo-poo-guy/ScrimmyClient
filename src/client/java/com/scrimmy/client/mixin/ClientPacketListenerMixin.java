package com.scrimmy.client.mixin;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.visual.HitParticles;
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
