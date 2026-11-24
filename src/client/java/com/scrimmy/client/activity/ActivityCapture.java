package com.scrimmy.client.activity;

import com.scrimmy.client.module.basefinding.ActivityLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Packet-position cache: sightings use server coordinates, never interpolated render positions. */
public final class ActivityCapture {
    private static final class Sight {
        final UUID uuid;
        String name;
        final VecDeltaCodec codec=new VecDeltaCodec();
        final EnumMap<EquipmentSlot,String> equipment=new EnumMap<>(EquipmentSlot.class);
        Sight(UUID uuid,String name,Vec3 position){this.uuid=uuid;this.name=name;codec.setBase(position);}
    }
    private static final class ClusterSight {
        final EntityType<?> type;
        final VecDeltaCodec codec=new VecDeltaCodec();
        ClusterSight(EntityType<?> type,Vec3 position){this.type=type;codec.setBase(position);}
    }
    private final Map<Integer,ClusterSight> clusters=new HashMap<>();
    private final ActivityLog module;
    private final Map<Integer,Sight> players=new HashMap<>();
    public ActivityCapture(ActivityLog module){this.module=module;}
    public void clear(){players.clear();clusters.clear();}
    public void add(ClientboundAddEntityPacket packet){
        if(!module.active())return;
        if(packet.getType()==EntityType.PLAYER){
            var connection=Minecraft.getInstance().getConnection();
            var info=connection==null?null:connection.getPlayerInfo(packet.getUUID());
            String name=info==null?"Unknown player":info.getProfile().name();
            Sight sight=new Sight(packet.getUUID(),name,new Vec3(packet.getX(),packet.getY(),packet.getZ()));
            if(players.size()>=4096)players.remove(players.keySet().iterator().next());
            players.put(packet.getId(),sight);record(sight);
        } else if(module.wantsCluster(packet.getType())) {
            Vec3 position=new Vec3(packet.getX(),packet.getY(),packet.getZ());
            if(clusters.size()<16384)clusters.put(packet.getId(),new ClusterSight(packet.getType(),position));
            module.cluster(packet.getId(),packet.getType(),position);
        }
    }
    public void remove(ClientboundRemoveEntitiesPacket packet){module.removeClusters(packet.getEntityIds());for(int id:packet.getEntityIds()){players.remove(id);clusters.remove(id);}}
    public void move(int id,ClientboundMoveEntityPacket packet){
        if(!module.active())return;
        Sight sight=players.get(id);
        if(sight!=null){
            if(packet.hasPosition())sight.codec.setBase(sight.codec.decode(packet.getXa(),packet.getYa(),packet.getZa()));
            record(sight);
        }else if(clusters.containsKey(id)) {
            ClusterSight cluster=clusters.get(id);
            if(packet.hasPosition())cluster.codec.setBase(cluster.codec.decode(packet.getXa(),packet.getYa(),packet.getZa()));
            if(module.wantsCluster(cluster.type))module.cluster(id,cluster.type,cluster.codec.getBase());else clusters.remove(id);
        }else{
            Entity entity=Minecraft.getInstance().level.getEntity(id);
            if(entity!=null){Vec3 pos=packet.hasPosition()?entity.getPositionCodec().decode(packet.getXa(),packet.getYa(),packet.getZa()):entity.position();module.entity(entity,pos);}
        }
    }
    public void sync(int id,Vec3 position){
        if(!module.active())return;
        Sight sight=players.get(id);
        if(sight!=null){sight.codec.setBase(position);record(sight);}
        else if(clusters.containsKey(id)) {var cluster=clusters.get(id);cluster.codec.setBase(position);module.cluster(id,cluster.type,position);}
        else {Entity entity=Minecraft.getInstance().level.getEntity(id);if(entity!=null)module.entity(entity,position);}
    }
    public void teleport(ClientboundTeleportEntityPacket packet){
        if(!module.active())return;
        Sight sight=players.get(packet.id());Entity entity=Minecraft.getInstance().level.getEntity(packet.id());
        Vec3 base=sight!=null?sight.codec.getBase():clusters.containsKey(packet.id())?clusters.get(packet.id()).codec.getBase():entity==null?null:entity.position();
        if(base==null)return;
        var change=packet.change();Vec3 p=change.position();
        sync(packet.id(),new Vec3(p.x+(packet.relatives().contains(Relative.X)?base.x:0),
                p.y+(packet.relatives().contains(Relative.Y)?base.y:0),p.z+(packet.relatives().contains(Relative.Z)?base.z:0)));
    }
    public void equipment(ClientboundSetEquipmentPacket packet){
        if(!module.active())return;
        Sight sight=players.get(packet.getEntity());if(sight==null)return;
        for(var pair:packet.getSlots())sight.equipment.put(pair.getFirst(),item(pair.getSecond()));record(sight);
    }
    public void update(int id){
        if(!module.active())return;
        Sight sight=players.get(id);if(sight!=null){record(sight);return;}
        ClusterSight cluster=clusters.get(id);
        if(cluster!=null){module.cluster(id,cluster.type,cluster.codec.getBase());return;}
        module.entity(Minecraft.getInstance().level.getEntity(id));
    }
    private String item(ItemStack stack){return stack.isEmpty()?"empty":BuiltInRegistries.ITEM.getKey(stack.getItem())+" x"+stack.getCount();}
    private void record(Sight s){
        var connection=Minecraft.getInstance().getConnection();var info=connection==null?null:connection.getPlayerInfo(s.uuid);
        if(info!=null)s.name=info.getProfile().name();
        Vec3 p=s.codec.getBase();
        String gear="hands: "+s.equipment.getOrDefault(EquipmentSlot.MAINHAND,"unknown")+" / "+s.equipment.getOrDefault(EquipmentSlot.OFFHAND,"unknown")
                +" · armor: "+s.equipment.getOrDefault(EquipmentSlot.HEAD,"unknown")+", "+s.equipment.getOrDefault(EquipmentSlot.CHEST,"unknown")+", "
                +s.equipment.getOrDefault(EquipmentSlot.LEGS,"unknown")+", "+s.equipment.getOrDefault(EquipmentSlot.FEET,"unknown");
        module.player(s.name,s.uuid,p.x,p.y,p.z,gear);
    }
}
