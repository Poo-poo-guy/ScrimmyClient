package com.scrimmy.client.activity;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check hooks against the actual mapped 1.21.11 bytecode without launching a GL window. */
class ActivityPacketHooksTest {
    private ClassNode read(String name)throws Exception{
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,name);ClassNode node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private Object value(AnnotationNode node,String name){for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(name))return node.values.get(i+1);return null;}
    @Test void allEarlyHooksRunImmediatelyAfterTheClientThreadHandoff()throws Exception{
        ClassNode listener=read("net/minecraft/client/multiplayer/ClientPacketListener"),mixin=read("com/scrimmy/client/mixin/ActivityPacketListenerMixin");
        Set<String> early=new HashSet<>();
        for(MethodNode hook:mixin.methods){
            if(hook.visibleAnnotations==null)continue;
            for(AnnotationNode inject:hook.visibleAnnotations){
                if(!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))continue;
                @SuppressWarnings("unchecked") List<String> methods=(List<String>)value(inject,"method");
                @SuppressWarnings("unchecked") List<AnnotationNode> anchors=(List<AnnotationNode>)value(inject,"at");
                for(String method:methods){
                    String packet=Type.getArgumentTypes(hook.desc)[0].getDescriptor();
                    MethodNode target=listener.methods.stream().filter(m->m.name.equals(method)&&m.desc.startsWith("("+packet+")")).findFirst().orElseThrow(()->new AssertionError(method));
                    for(AnnotationNode at:anchors){
                        if(!value(at,"value").equals("INVOKE"))continue;
                        early.add(method);String invocation=(String)value(at,"target");
                        assertEquals("AFTER",((String[])value(at,"shift"))[1]);
                        int count=0;
                        for(AbstractInsnNode instruction:target.instructions){
                            if(instruction instanceof MethodInsnNode call && ("L"+call.owner+";"+call.name+call.desc).equals(invocation))count++;
                        }
                        assertEquals(1,count,method+" must have exactly one thread handoff anchor");
                        assertTrue(invocation.contains("PacketUtils;ensureRunningOnSameThread"));
                    }
                }
            }
        }
        assertTrue(early.containsAll(Set.of("handleSoundEvent","handleSoundEntityEvent","handleLevelEvent","handleExplosion","handleAddEntity","handleBlockUpdate","handleChunkBlocksUpdate","handleMoveEntity","handleEntityPositionSync","handleTeleportEntity","handleSetEquipment","handleBlockEvent","handleRemoveEntities")));
        ClassNode move=read("net/minecraft/network/protocol/game/ClientboundMoveEntityPacket");
        assertTrue(move.fields.stream().anyMatch(f->f.name.equals("entityId")&&f.desc.equals("I")));
    }
    @Test void bothMixinsAreClientOnlyAndAllProductionFeatureCodeStaysInClientSourceSet()throws Exception{
        var config=JsonParser.parseString(Files.readString(Path.of("src/client/resources/scrimmyclient.mixins.json"))).getAsJsonObject();
        Set<String> mixins=new HashSet<>();config.getAsJsonArray("client").forEach(v->mixins.add(v.getAsString()));
        assertTrue(mixins.containsAll(Set.of("ActivityPacketListenerMixin","MoveEntityPacketAccessor")));
        try(var files=Files.walk(Path.of("src/main/java"))){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith("Activity")));}
        for(String name:List.of("ActivityEntry","ActivityCluster","ActivityCapture","ActivityHistory","ActivityWaypoints")){
            ClassNode node=read("com/scrimmy/client/activity/"+name);
            for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions){
                if(instruction instanceof MethodInsnNode call){
                    assertFalse(call.owner.startsWith("java/net/"),call.owner+" must not initiate network I/O");
                    assertFalse(call.owner.equals("net/minecraft/core/Registry")&&call.name.startsWith("register"),call.name);
                }
            }
        }
    }
}
