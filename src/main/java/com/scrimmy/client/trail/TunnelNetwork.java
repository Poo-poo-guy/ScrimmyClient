package com.scrimmy.client.trail;

import java.util.*;
import static com.scrimmy.client.trail.TunnelAnalysis.*;

/** Joins regular sections, bridges small intersections and splits paths at every branch. */
public final class TunnelNetwork {
    public static final int MAX_CONNECTORS = 65536, MAX_RAY_POSITIONS = 65536;
    public static final double MINIMUM_BRANCH_SPAN = 4, MINIMUM_REGULAR_FRACTION = .65;
    public enum Kind {
        END("Dead end"), FRONTIER("Unexplored"), JUNCTION("Junction"), ROOM("Room opening"),
        LAVA("Lava"), DROP("Deep drop"), OBSTACLE("Obstacle");
        public final String label;
        Kind(String label) { this.label = label; }
        public boolean hazard() { return ordinal() >= LAVA.ordinal(); }
    }
    public record Marker(long key, Kind kind) {}
    public record Path(long[] points) {}
    public record Network(List<Path> paths, List<Marker> markers, Set<Long> points) {
        public static final Network EMPTY = new Network(List.of(), List.of(), Set.of());
    }
    private static final class Node {
        final Sample sample;
        final boolean regular;
        final Set<Long> neighbors = new LinkedHashSet<>();
        Node(Sample sample, boolean regular) { this.sample = sample; this.regular = regular; }
    }
    private record Ray(long source, int direction, int length) {}
    private record Edge(long a, long b) {
        static Edge of(long a, long b) { return a < b ? new Edge(a, b) : new Edge(b, a); }
    }
    private TunnelNetwork() {}

    public static Network build(Collection<Chunk> chunks, int minimumLength) {
        Map<Long, Node> nodes = new LinkedHashMap<>();
        for (Chunk chunk : chunks) for (Sample s : chunk.samples()) nodes.put(s.key(), new Node(s, true));
        List<Node> regular = new ArrayList<>(nodes.values());
        for (Node n : regular) {
            if (Thread.currentThread().isInterrupted()) return Network.EMPTY;
            Sample a = n.sample;
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                long key = TrailMath.key(a.x() + dx, a.y() + dy, a.z() + dz);
                Node other = nodes.get(key);
                int movement = (dx != 0 ? 1 : 0) | (dy != 0 ? 2 : 0) | (dz != 0 ? 4 : 0);
                // Diagonal neighbors cover stairs, but never connect two parallel centers sideways.
                if (other != null && (a.axes() & other.sample.axes() & movement) != 0) connect(nodes, a.key(), key);
            }
        }
        Map<Long, List<Ray>> rays = new HashMap<>();
        for (Node n : regular) for (int dir = 0; dir < 6; dir++) {
            int length = n.sample.reach(dir);
            if (length == 0 || forward(n, dir)) continue;
            for (int step = 1; step <= length; step++) {
                long key = offset(n.sample.key(), dir, step);
                Node hit = nodes.get(key);
                if (hit != null && hit.regular) { bridge(nodes, n.sample.key(), dir, step); break; }
                if (!rays.containsKey(key) && rays.size() >= MAX_RAY_POSITIONS) break;
                List<Ray> crossing = rays.computeIfAbsent(key, unused -> new ArrayList<>());
                for (Ray other : crossing) {
                    if (other.source == n.sample.key() || other.direction == dir) continue;
                    // Two independently verified air rays meet at one shared junction, rather than
                    // making a clique of diagonal shortcuts between the entrances to a room.
                    bridge(nodes, n.sample.key(), dir, step);
                    bridge(nodes, other.source, other.direction, other.length);
                }
                crossing.add(new Ray(n.sample.key(), dir, step));
            }
        }
        pruneTinySpurs(nodes);
        List<Path> paths = new ArrayList<>(); List<Marker> markers = new ArrayList<>();
        Set<Long> accepted = new HashSet<>(), seen = new HashSet<>();
        Set<Edge> walked = new HashSet<>(); Set<String> hazardCells = new HashSet<>();
        for (Node first : nodes.values()) {
            if (!seen.add(first.sample.key())) continue;
            List<Node> component = new ArrayList<>(); ArrayDeque<Node> queue = new ArrayDeque<>(); queue.add(first);
            int regularCount = 0; double length = 1;
            while (!queue.isEmpty()) {
                Node node = queue.removeFirst(); component.add(node); if (node.regular) regularCount++;
                for (long next : node.neighbors) {
                    length += TrailMath.distance(node.sample.key(), next) * .5;
                    if (seen.add(next)) queue.add(nodes.get(next));
                }
            }
            // A short pocket cannot qualify merely because a bridge was added across open air.
            if (length < minimumLength || regularCount < Math.max(3, minimumLength * MINIMUM_REGULAR_FRACTION)) continue;
            for (Node n : component) {
                accepted.add(n.sample.key());
                int degree = n.neighbors.size(), outward = 0;
                for (int d = 0; d < 6; d++)
                    if ((n.sample.axes() & 1 << (d / 2)) != 0 && !forward(n, d)) outward |= 1 << d;
                if (degree >= 3) markers.add(new Marker(n.sample.key(), Kind.JUNCTION));
                else if (degree == 1) markers.add(new Marker(n.sample.key(),
                        (n.sample.frontier() & outward) != 0 ? Kind.FRONTIER :
                        (n.sample.rooms() & outward) != 0 ? Kind.ROOM : Kind.END));
                int hazards = n.sample.hazards();
                if (hazards != 0) {
                    Kind kind = (hazards & HAZARD_LAVA) != 0 ? Kind.LAVA : (hazards & HAZARD_DROP) != 0 ? Kind.DROP : Kind.OBSTACLE;
                    String cell = Math.floorDiv(n.sample.x(), 6) + ":" + Math.floorDiv(n.sample.y(), 6)
                            + ":" + Math.floorDiv(n.sample.z(), 6) + ":" + kind;
                    if (hazardCells.add(cell)) markers.add(new Marker(n.sample.key(), kind));
                }
                if (degree != 2) for (long next : n.neighbors) walk(nodes, n.sample.key(), next, walked, paths);
            }
            // Closed loops have no degree-one/three seed, but still need a continuous trail.
            for (Node n : component) for (long next : n.neighbors) walk(nodes, n.sample.key(), next, walked, paths);
        }
        return new Network(List.copyOf(paths), List.copyOf(markers), Set.copyOf(accepted));
    }
    private static void pruneTinySpurs(Map<Long, Node> nodes) {
        Set<Long> remove = new HashSet<>();
        for (Node leaf : nodes.values()) {
            if (leaf.neighbors.size() != 1) continue;
            int outward = 0;
            for (int d = 0; d < 6; d++)
                if ((leaf.sample.axes() & 1 << (d / 2)) != 0 && !forward(leaf, d)) outward |= 1 << d;
            if (((leaf.sample.frontier() | leaf.sample.rooms()) & outward) != 0) continue;
            List<Long> branch = new ArrayList<>(); branch.add(leaf.sample.key());
            long previous = leaf.sample.key(), current = leaf.neighbors.iterator().next();
            double length = TrailMath.distance(previous, current);
            while (length < MINIMUM_BRANCH_SPAN && nodes.get(current).neighbors.size() == 2) {
                branch.add(current);
                var iterator = nodes.get(current).neighbors.iterator();
                long next = iterator.next(); if (next == previous) next = iterator.next();
                length += TrailMath.distance(current, next); previous = current; current = next;
            }
            // A stair lip/end cap can resemble a tiny perpendicular shaft. It is not a branch.
            if (length < MINIMUM_BRANCH_SPAN && nodes.get(current).neighbors.size() >= 3) remove.addAll(branch);
        }
        for (long key : remove) {
            Node old = nodes.remove(key);
            for (long neighbor : old.neighbors) if (nodes.containsKey(neighbor)) nodes.get(neighbor).neighbors.remove(key);
        }
    }
    private static void walk(Map<Long, Node> nodes, long start, long next, Set<Edge> walked, List<Path> paths) {
        if (!walked.add(Edge.of(start, next))) return;
        List<Long> points = new ArrayList<>(); points.add(start);
        long current = next;
        while (true) {
            points.add(current); Node node = nodes.get(current);
            if (node.neighbors.size() != 2 || current == start) break;
            var iterator = node.neighbors.iterator();
            long following = iterator.next(); if (following == previousKey(points)) following = iterator.next();
            if (!walked.add(Edge.of(current, following))) break;
            current = following;
        }
        long[] array = new long[points.size()]; for (int i = 0; i < array.length; i++) array[i] = points.get(i);
        paths.add(new Path(array));
    }
    private static long previousKey(List<Long> points) { return points.get(points.size() - 2); }
    private static boolean forward(Node node, int direction) {
        for (long key : node.neighbors) {
            int delta = (TrailMath.x(key) - node.sample.x()) * DX[direction]
                    + (TrailMath.y(key) - node.sample.y()) * DY[direction]
                    + (TrailMath.z(key) - node.sample.z()) * DZ[direction];
            if (delta > 0) return true;
        }
        return false;
    }
    private static long offset(long key, int direction, int step) {
        return TrailMath.key(TrailMath.x(key) + DX[direction] * step, TrailMath.y(key) + DY[direction] * step,
                TrailMath.z(key) + DZ[direction] * step);
    }
    private static void bridge(Map<Long, Node> nodes, long source, int direction, int length) {
        if (nodes.size() + length > TrailMemory.MAX_SAMPLES + MAX_CONNECTORS) return;
        long previous = source;
        for (int step = 1; step <= length; step++) {
            long key = offset(source, direction, step);
            nodes.computeIfAbsent(key, unused -> new Node(new Sample(key, 1 << (direction / 2), 0, 0, 0, 0), false));
            connect(nodes, previous, key); previous = key;
        }
    }
    private static void connect(Map<Long, Node> nodes, long a, long b) {
        nodes.get(a).neighbors.add(b); nodes.get(b).neighbors.add(a);
    }
}
