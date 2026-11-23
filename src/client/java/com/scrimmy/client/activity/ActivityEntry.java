package com.scrimmy.client.activity;

/** Immutable values are the only objects passed between the client and history worker. */
public record ActivityEntry(String id, String session, String dimension, String category,
        String identity, String details, String scoringId, double x, double y, double z,
        long firstSeen, long lastSeen, long hits, boolean underground, boolean confirmed,
        int count, double score) {
    public String coordinates() { return (int)Math.floor(x) + ", " + (int)Math.floor(y) + ", " + (int)Math.floor(z); }
    public String chunk() { return dimension + ":" + ((int)Math.floor(x) >> 4) + ":" + ((int)Math.floor(z) >> 4); }
    public double distanceSquared(double px, double py, double pz) {
        double dx=x-px, dy=y-py, dz=z-pz; return dx*dx+dy*dy+dz*dz;
    }
    public boolean valid() {
        return id != null && session != null && dimension != null && category != null && identity != null
                && details != null && scoringId != null && Double.isFinite(x) && Double.isFinite(y)
                && Double.isFinite(z) && Double.isFinite(score) && hits > 0 && firstSeen <= lastSeen
                && details.length() <= 8192 && identity.length() <= 256;
    }
}
