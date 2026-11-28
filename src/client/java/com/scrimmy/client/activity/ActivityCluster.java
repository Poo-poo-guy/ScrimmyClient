package com.scrimmy.client.activity;

/** Packet snapshot only: no Minecraft entity is shared with the history worker. */
public record ActivityCluster(String session,String dimension,int entityId,String category,double x,double y,double z,
        boolean underground,long time,boolean removed) {}
