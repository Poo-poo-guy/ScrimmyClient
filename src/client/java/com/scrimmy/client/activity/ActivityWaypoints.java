package com.scrimmy.client.activity;

import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import java.util.Optional;
import java.util.UUID;

/** A single local locator-bar waypoint; never sends a tracking packet or registers a style. */
public final class ActivityWaypoints {
    private ClientWaypointManager manager;
    private TrackedWaypoint waypoint;
    private String entryId;
    public void select(ClientWaypointManager next,ActivityEntry entry,int color) {
        clear();
        var icon=new Waypoint.Icon(); icon.color=Optional.of(color&0xffffff);
        waypoint=TrackedWaypoint.setPosition(UUID.randomUUID(),icon,BlockPos.containing(entry.x(),entry.y(),entry.z()));
        manager=next;entryId=entry.id();manager.trackWaypoint(waypoint);
    }
    public void remove(String id) { if(id.equals(entryId))clear(); }
    public void clear() {
        if(manager!=null&&waypoint!=null)manager.untrackWaypoint(waypoint);
        manager=null;waypoint=null;entryId=null;
    }
}
