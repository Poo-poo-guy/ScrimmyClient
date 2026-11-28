package com.scrimmy.client.activity;

import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ActivityWaypointsTest {
    private ActivityEntry entry(String id){return new ActivityEntry(id,"s","overworld","Player","uuid","Alt","player",1,30,2,1,1,1,false,false,1,1);}
    @Test void localWaypointIsReplacedAndClearedWithoutRemovingServerWaypoints(){
        var manager=new ClientWaypointManager();var local=new ActivityWaypoints();
        local.select(manager,entry("a"),0xff819ab7);assertTrue(manager.hasWaypoints());
        local.select(manager,entry("b"),0xffed7587);local.remove("a");assertTrue(manager.hasWaypoints());
        local.remove("b");assertFalse(manager.hasWaypoints());
        var server=TrackedWaypoint.setPosition(UUID.randomUUID(),new Waypoint.Icon(),BlockPos.ZERO);manager.trackWaypoint(server);
        local.select(manager,entry("c"),0xff819ab7);local.clear();assertTrue(manager.hasWaypoints());
        manager.untrackWaypoint(server);assertFalse(manager.hasWaypoints());
    }
}
