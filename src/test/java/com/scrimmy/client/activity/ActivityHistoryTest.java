package com.scrimmy.client.activity;

import com.google.gson.Gson;
import com.scrimmy.client.sound.SoundTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ActivityHistoryTest {
    @TempDir Path directory;
    private static final SoundTracker.Weights WEIGHTS=new SoundTracker.Weights(1,2,4,12,1,1.5,4,10000,120000);
    private ActivityHistory.Policy policy(int limit,int days,int bytes){return new ActivityHistory.Policy(8,30000,limit,days,bytes,WEIGHTS);}
    private ActivityEntry event(String id,String session,String dimension,String category,String identity,String sound,double x,long time,boolean below){
        return new ActivityEntry(id,session,dimension,category,identity,identity,sound,x,30,1,time,time,1,below,false,1,0);
    }
    private ActivityEntry sound(String id,double x,long time){return event(id,"session","minecraft:overworld","Sound","chest","block.chest.open",x,time,false);}
    private void await(ActivityHistory history)throws Exception{history.flush().get(5,TimeUnit.SECONDS);assertEquals("",history.error());}
    private Path file(String server)throws Exception{return directory.resolve(ActivityHistory.serverKey(server)+".json");}
    @Test void mergesAcrossNegativeCellBoundariesAndKeepsDimensionsSessionsIdentitiesSeparate()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));
            history.accept(List.of(sound("a",-1,now),sound("b",1,now+1),
                    event("c","other session","minecraft:overworld","Sound","chest","block.chest.open",1,now,false),
                    event("d","session","minecraft:the_nether","Sound","chest","block.chest.open",1,now,false),
                    event("e","session","minecraft:overworld","Player","uuid","player",1,now,false),sound("far",100,now)));
            await(history);assertEquals(5,history.snapshot().size());
            var merged=history.snapshot().stream().filter(e->e.id().equals("a")).findFirst().orElseThrow();
            assertEquals(2,merged.hits());assertEquals(now,merged.firstSeen());assertEquals(now+1,merged.lastSeen());assertEquals(1,merged.x());
        }
    }
    @Test void sharedScoringAddsUndergroundAndDifferentEventTypeBonuses()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));
            history.accept(List.of(event("s","session","overworld","Sound","piston","block.piston.extend",1,now,true),
                    event("b","session","overworld","Block","piston","piston",2,now,true)));
            await(history);
            var sound=history.snapshot().stream().filter(e->e.id().equals("s")).findFirst().orElseThrow();
            assertEquals(2+4+1.5,sound.score());assertEquals(1,SoundTracker.severity(sound.score(),4,12));
        }
    }
    @Test void repeatsSaturateAndAMergeOutsideTheTimeWindowCreatesAnotherEntry()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));
            List<ActivityEntry> burst=new ArrayList<>();for(int i=0;i<300;i++)burst.add(sound("s"+i,0,now));
            history.accept(burst);await(history);assertEquals(300,history.snapshot().getFirst().hits());assertEquals(128,history.snapshot().getFirst().score());
            history.accept(List.of(sound("later",0,now+31000)));await(history);assertEquals(2,history.snapshot().size());
        }
    }
    @Test void disconnectFlushAndRelogLoadCannotLoseEventsQueuedDuringLoadOrLeakServers()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("A",policy(5000,0,1000000));history.accept(List.of(sound("a",0,now)));
            history.open(null,policy(5000,0,1000000));history.open("B",policy(5000,0,1000000));history.accept(List.of(sound("b",40,now)));
            await(history);assertEquals("b",history.snapshot().getFirst().id());
            history.open("A",policy(5000,0,1000000));
            history.accept(List.of(event("relog","new session","minecraft:overworld","Sound","chest","block.chest.open",0,now,false)));
            await(history);assertEquals(Set.of("a","relog"),history.snapshot().stream().map(ActivityEntry::id).collect(java.util.stream.Collectors.toSet()));
        }
        try(var history=new ActivityHistory(directory)){
            history.open("B",policy(5000,0,1000000));await(history);assertEquals(1,history.snapshot().size());assertEquals("b",history.snapshot().getFirst().id());
        }
    }
    @Test void prunesLowestScoreThenOldestAndWritesBoundedValidJson()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(2,0,1000000));
            history.accept(List.of(sound("old weak",0,now-100),sound("new weak",40,now),
                    event("critical","session","overworld","Sound","tnt","explosion",80,now-1000,false)));
            await(history);assertEquals(Set.of("new weak","critical"),history.snapshot().stream().map(ActivityEntry::id).collect(java.util.stream.Collectors.toSet()));
            history.policy(policy(2,0,500));await(history);
            assertTrue(Files.size(file("server"))<=500);
            ActivityEntry[] entries=new Gson().fromJson(Files.readString(file("server")),ActivityEntry[].class);
            assertEquals(1,entries.length);assertEquals("critical",entries[0].id());
        }
    }
    @Test void expiryPersistsAndZeroDaysKeepsOldEntries()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));history.accept(List.of(sound("old",0,now-3*86400000L),sound("fresh",40,now)));await(history);
            assertEquals(2,history.snapshot().size());history.policy(policy(5000,1,1000000));await(history);assertEquals(1,history.snapshot().size());
        }
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));await(history);assertEquals("fresh",history.snapshot().getFirst().id());
        }
    }
    @Test void clustersRecordCentersAndCountsRatherThanIndividualEntities()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));
            history.clusters(List.of(new ActivityCluster("s","overworld",1,"Items",2,30,2,true,now,false)));await(history);assertTrue(history.snapshot().isEmpty());
            history.clusters(List.of(new ActivityCluster("s","overworld",2,"Items",6,30,6,true,now+1,false)));await(history);
            assertEquals(1,history.snapshot().size());var cluster=history.snapshot().getFirst();assertEquals(4,cluster.x());assertEquals(4,cluster.z());assertEquals(2,cluster.count());
            history.clusters(List.of(new ActivityCluster("s","overworld",1,"",0,0,0,false,now+2,true)));
            history.clusters(List.of(new ActivityCluster("s","overworld",3,"Items",10,30,6,true,now+3,false)));await(history);
            assertEquals(2,history.snapshot().getFirst().count());assertEquals(2,history.snapshot().getFirst().hits());
        }
    }
    @Test void radiusChangesRebuildIndexAndClearDeleteArePersisted()throws Exception{
        long now=System.currentTimeMillis();
        try(var history=new ActivityHistory(directory)){
            history.open("server",policy(5000,0,1000000));history.accept(List.of(sound("a",0,now)));await(history);
            history.policy(new ActivityHistory.Policy(16,30000,5000,0,1000000,WEIGHTS));history.accept(List.of(sound("b",12,now+1)));await(history);assertEquals(2,history.snapshot().getFirst().hits());
            history.remove("a");await(history);assertTrue(history.snapshot().isEmpty());
            history.accept(List.of(sound("c",40,now)));history.clear();await(history);assertEquals("[]",Files.readString(file("server")));
        }
    }
    @Test void invalidFileIsPreservedAndCannotRedirectNextServersEvents()throws Exception{
        Files.writeString(file("broken"),"not JSON");
        try(var history=new ActivityHistory(directory)){
            history.open("broken",policy(5000,0,1000000));history.flush().get(5,TimeUnit.SECONDS);assertFalse(history.ready());assertFalse(history.error().isEmpty());
            history.accept(List.of(sound("lost",0,System.currentTimeMillis())));history.open("good",policy(5000,0,1000000));history.accept(List.of(sound("good",0,System.currentTimeMillis())));await(history);
            assertEquals("not JSON",Files.readString(file("broken")));assertEquals("good",history.snapshot().getFirst().id());
        }
    }
}
