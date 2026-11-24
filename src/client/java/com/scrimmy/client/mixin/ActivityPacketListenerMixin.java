package com.scrimmy.client.mixin;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.ActivityLog;
import com.scrimmy.client.sound.SoundCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After the thread handoff, before vanilla can reject unloaded blocks, sounds or spawn data. */
@Mixin(ClientPacketListener.class)
abstract class ActivityPacketListenerMixin {
    @Unique private static final String SCRIMMY_THREAD="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";
    @Unique private ActivityLog scrimmy$activity(){return ScrimmyClient.MODULES.find(ActivityLog.class);}
    @Inject(method="handleSoundEvent",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$sound(ClientboundSoundPacket p,CallbackInfo ci){
        ActivityLog m=scrimmy$activity();if(!m.enabled())return;
        var sound=SoundCapture.read(p);m.sound(sound.x(),sound.y(),sound.z(),sound.id(),sound.category());
    }
    @Inject(method="handleSoundEntityEvent",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$entitySound(ClientboundSoundEntityPacket p,CallbackInfo ci){
        ActivityLog m=scrimmy$activity();if(!m.active())return;
        var e=Minecraft.getInstance().level.getEntity(p.getId());
        if(e!=null)m.sound(e.getX(),e.getY(),e.getZ(),p.getSound().value().location().toString(),p.getSource().getName());
    }
    @Inject(method="handleLevelEvent",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$event(ClientboundLevelEventPacket p,CallbackInfo ci){
        ActivityLog m=scrimmy$activity();if(!m.active())return;
        m.worldEvent(p.getPos(),p.getType(),p.getData());
        String sound=SoundCapture.levelSound(Minecraft.getInstance().level,p);
        if(sound!=null)m.sound(p.getPos().getX(),p.getPos().getY(),p.getPos().getZ(),sound,com.scrimmy.client.sound.SoundLevelEvents.category(p.getType()));
    }
    @Inject(method="handleExplosion",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$explosion(ClientboundExplodePacket p,CallbackInfo ci){scrimmy$activity().explosion(p.center().x,p.center().y,p.center().z);}
    @Inject(method="handleBlockUpdate",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$block(ClientboundBlockUpdatePacket p,CallbackInfo ci){scrimmy$activity().block(p.getPos(),p.getBlockState());}
    @Inject(method="handleChunkBlocksUpdate",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$delta(ClientboundSectionBlocksUpdatePacket p,CallbackInfo ci){ActivityLog m=scrimmy$activity();if(m.active()&&m.blocks.get())p.runUpdates(m::block);}
    @Inject(method="handleBlockEvent",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$blockEvent(ClientboundBlockEventPacket p,CallbackInfo ci){scrimmy$activity().blockEvent(p.getPos(),p.getBlock(),p.getB0(),p.getB1());}
    @Inject(method="handleAddEntity",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$spawn(ClientboundAddEntityPacket p,CallbackInfo ci){scrimmy$activity().capture.add(p);}
    @Inject(method="handleMoveEntity",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$move(ClientboundMoveEntityPacket p,CallbackInfo ci){scrimmy$activity().capture.move(((MoveEntityPacketAccessor)p).scrimmy$entityId(),p);}
    @Inject(method="handleMinecartAlongTrack",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$minecart(ClientboundMoveMinecartPacket p,CallbackInfo ci){
        if(!p.lerpSteps().isEmpty())scrimmy$activity().capture.sync(p.entityId(),p.lerpSteps().getLast().position());
    }
    @Inject(method="handleEntityPositionSync",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$sync(ClientboundEntityPositionSyncPacket p,CallbackInfo ci){scrimmy$activity().capture.sync(p.id(),p.values().position());}
    @Inject(method="handleTeleportEntity",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$teleport(ClientboundTeleportEntityPacket p,CallbackInfo ci){scrimmy$activity().capture.teleport(p);}
    @Inject(method="handleSetEquipment",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$equipment(ClientboundSetEquipmentPacket p,CallbackInfo ci){scrimmy$activity().capture.equipment(p);}
    @Inject(method="handleSetEntityData",at=@At("TAIL"))
    private void scrimmy$data(ClientboundSetEntityDataPacket p,CallbackInfo ci){scrimmy$activity().capture.update(p.id());}
    @Inject(method="handleRemoveEntities",at=@At(value="INVOKE",target=SCRIMMY_THREAD,shift=At.Shift.AFTER))
    private void scrimmy$remove(ClientboundRemoveEntitiesPacket p,CallbackInfo ci){scrimmy$activity().capture.remove(p);}
}
