package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.setting.GradientSetting;
import com.scrimmy.client.trail.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import java.util.*;

/** Spline samples, strings, keys and marker arrays are prepared on ticks, never on render frames. */
public final class DrillTrailRenderer implements AutoCloseable {
    public static final double FADE_START_FRACTION = .65, FADE_SECONDS = .4, VISITED_OPACITY = .22;
    public static final int SPLINE_STEPS = 3, MAX_SEGMENTS = 8192, MAX_MARKERS = 48;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private final DrillTrail module;
    private final DrillTrailNametags nametags;
    private final WorldOverlayRenderer geometry = new WorldOverlayRenderer("Scrimmy DrillTrail");
    private final Matrix4f view = new Matrix4f(), culling = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Map<SegmentKey, Segment> cache = new HashMap<>();
    private final Map<TunnelNetwork.Marker, Marker> markerCache = new HashMap<>();
    private Segment[] segments = new Segment[0];
    private Marker[] markers = new Marker[0];
    private final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
    private final PanelBlur blur = new PanelBlur();
    private ScreenRectangle screen;
    private int screenWidth, screenHeight, hintWidth;
    private String hint;
    private boolean hintWanted;
    private double hintAlpha;
    private long lastFrame,extractionNanos;
    private record SegmentKey(long a, long b, int sample) {}
    private record Edge(long[] points, int index, double distance, double along, boolean reverse) {}
    private static final class Segment {
        double ax, ay, az, bx, by, bz, along, alpha, visited, boost;
        boolean wanted, targetVisited, plugged, built, smart;
        long previous,next;
        double targetBoost;
    }
    private static final class Marker {
        TunnelNetwork.Marker data;
        double alpha;
        boolean wanted, visited;
    }
    public DrillTrailRenderer(DrillTrail module) { this.module = module; nametags=new DrillTrailNametags(module); }
    public void initialize() {
        geometry.reserve(MAX_SEGMENTS * 2 * 12 + MAX_MARKERS * 2 * 64);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "drill_trail"), (g, delta) -> hud(g));
    }
    public void clear() {
        nametags.clear(); cache.clear(); markerCache.clear(); segments = new Segment[0]; markers = new Marker[0];
        geometry.clear(); hint = null; hintWanted = false; hintAlpha = 0; lastFrame = 0; blur.close();
    }
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level != module.world()) return;
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (screen == null || width != screenWidth || height != screenHeight) {
            screenWidth = width; screenHeight = height; screen = new ScreenRectangle(0, 0, width, height);
        }
        long timing=TrailTimings.start();
        nametags.refresh(mc);
        cache.values().removeIf(v -> !v.wanted && v.alpha < .002);
        markerCache.values().removeIf(v -> !v.wanted && v.alpha < .002);
        for (Segment v : cache.values()) v.wanted = false;
        for (Marker v : markerCache.values()) v.wanted = false;
        hintWanted = false;
        if (module.enabled()) {
            double px = mc.player.getX(), py = mc.player.getY() + .9, pz = mc.player.getZ();
            double range = module.range.get(), rangeSquared = range * range;
            List<Edge> nearest = new ArrayList<>();
            for (var path : module.network().paths()) {
                long[] points = path.points(); if (points.length < 2) continue;
                boolean firstVisited = module.memory.visited(points[0]), lastVisited = module.memory.visited(points[points.length - 1]);
                boolean reverse = firstVisited != lastVisited ? lastVisited : distance(points[0], px, py, pz) > distance(points[points.length - 1], px, py, pz);
                double along = 0;
                for (int i = 0; i < points.length - 1; i++) {
                    double d = Math.min(distance(points[i], px, py, pz), distance(points[i + 1], px, py, pz));
                    if (d < rangeSquared) nearest.add(new Edge(points, i, d, along, reverse));
                    along += TrailMath.distance(points[i], points[i + 1]);
                }
            }
            nearest.sort(Comparator.comparingDouble(Edge::distance));
            for (int e = 0; e < Math.min(nearest.size(), MAX_SEGMENTS / SPLINE_STEPS); e++) {
                Edge edge = nearest.get(e); long[] points = edge.points; int i = edge.index;
                boolean closed = points[0] == points[points.length - 1];
                long a = points[i == 0 && closed ? points.length - 2 : Math.max(0, i - 1)];
                long b = points[i], c = points[i + 1];
                long d = points[i + 2 == points.length && closed ? 1 : Math.min(points.length - 1, i + 2)];
                boolean visited = module.memory.visited(b) && module.memory.visited(c);
                int bx = (int)Math.floor((TrailMath.x(b) + TrailMath.x(c) + 1) * .5) >> 4;
                int bz = (int)Math.floor((TrailMath.z(b) + TrailMath.z(c) + 1) * .5) >> 4;
                double score = module.smart()?module.baseScore(bx, bz):0;
                for (int step = 0; step < SPLINE_STEPS; step++) {
                    SegmentKey key = new SegmentKey(Math.min(b, c), Math.max(b, c), b < c ? step : SPLINE_STEPS - 1 - step);
                    Segment v = cache.get(key);
                    if (v == null) {
                        if (cache.size() >= MAX_SEGMENTS * 2) continue;
                        v = new Segment(); cache.put(key, v);
                    }
                    double t = step / (double)SPLINE_STEPS, next = (step + 1.0) / SPLINE_STEPS;
                    if(!v.built||v.previous!=a||v.next!=d||v.smart!=module.smart()) {
                    v.built=true;v.previous=a;v.next=d;v.smart=module.smart();
                    v.ax = spline(a, b, c, d, t, 0); v.ay = spline(a, b, c, d, t, 1); v.az = spline(a, b, c, d, t, 2);
                    v.bx = spline(a, b, c, d, next, 0); v.by = spline(a, b, c, d, next, 1); v.bz = spline(a, b, c, d, next, 2);
                    }
                    v.plugged=module.network().plugs().contains(b)||module.network().plugs().contains(c);
                    v.along = (edge.along + t * TrailMath.distance(b, c)) * (edge.reverse ? -1 : 1);
                    v.targetVisited = visited; v.targetBoost = score >= 75 ? score / 100 : 0; v.wanted = true;
                }
            }
            List<TunnelNetwork.Marker> nearby = new ArrayList<>();
            TunnelNetwork.Marker lead = null; double leadDistance = Double.MAX_VALUE;
            if(module.smart()) for (var marker : module.network().markers()) {
                double distance = distance(marker.key(), px, py, pz);
                if (distance > rangeSquared) continue;
                if (!marker.kind().hazard() && !module.memory.visited(marker.key()) && distance < leadDistance) {
                    lead = marker; leadDistance = distance;
                }
                nearby.add(marker);
            }
            nearby.sort(Comparator.comparingDouble(m -> distance(m.key(), px, py, pz)));
            for (int i = 0; i < Math.min(MAX_MARKERS, nearby.size()); i++) {
                var data = nearby.get(i); Marker visual = markerCache.get(data);
                if (visual == null) {
                    if (markerCache.size() >= MAX_MARKERS * 2) continue;
                    visual = new Marker(); markerCache.put(data, visual);
                }
                visual.data = data; visual.wanted = true; visual.visited = module.memory.visited(data.key());
            }
            if (lead != null) {
                double lx = TrailMath.x(lead.key()) + .5 - px, lz = TrailMath.z(lead.key()) + .5 - pz;
                double bearing = TrailMath.bearing(lx, lz, mc.player.getYRot());
                String direction = Math.abs(bearing) <= 30 ? "Ahead" : Math.abs(bearing) >= 150 ? "Behind" : bearing > 0 ? "Right" : "Left";
                int compass = Math.floorMod((int)Math.floor((Math.toDegrees(Math.atan2(lx, -lz)) + 22.5) / 45), 8);
                String vertical = TrailMath.y(lead.key()) - py > 3 ? " / Up" : TrailMath.y(lead.key()) - py < -3 ? " / Down" : "";
                hint = lead.kind().label + "  " + Math.round(Math.sqrt(leadDistance)) + " m  ·  " + direction + vertical + "  " + COMPASS[compass];
                hintWidth = ClientFonts.width(hint) + 22; hintWanted = module.smart();
            }
        }
        segments = cache.values().toArray(Segment[]::new); markers = markerCache.values().toArray(Marker[]::new);
        TrailTimings.end(TrailTimings.GEOMETRY,timing);
    }
    private double spline(long a, long b, long c, long d, double t, int axis) {
        return TrailMath.smoothSpline(coordinate(a,axis),coordinate(b,axis),coordinate(c,axis),
                coordinate(d,axis),t,module.smart())+.5;
    }
    private static int coordinate(long key, int axis) { return axis == 0 ? TrailMath.x(key) : axis == 1 ? TrailMath.y(key) : TrailMath.z(key); }
    private static double distance(long key, double x, double y, double z) {
        double dx = TrailMath.x(key) + .5 - x, dy = TrailMath.y(key) + .5 - y, dz = TrailMath.z(key) + .5 - z;
        return dx * dx + dy * dy + dz * dz;
    }
    public void extract(WorldExtractionContext context) {
        extractionNanos=0;
        geometry.clear(); Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level != module.world()) return;
        long timing=TrailTimings.start();
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 1.0 / 60 : Math.min(.25, (now - lastFrame) / 1e9); lastFrame = now;
        double response = 1 - Math.exp(-dt * 4 * ScrimmyClient.THEME.animationSpeed.get() / FADE_SECONDS);
        hintAlpha += ((hintWanted && module.enabled() && module.smart() ? 1 : 0) - hintAlpha) * response;
        view.set(context.viewMatrix()); culling.set(context.cullProjectionMatrix()).mul(view); frustum.set(culling); geometry.begin(view);
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z, range = module.range.get(), seconds = now / 1e9;
        nametags.project(culling,cx,cy,cz,range,response);
        int accent = ScrimmyClient.THEME.accent.get() | 0xff000000;
        for (Segment v : segments) {
            v.alpha += ((v.wanted && module.enabled() ? 1 : 0) - v.alpha) * response;
            v.visited += ((v.targetVisited && module.dimVisited.get() ? 1 : 0) - v.visited) * response;
            v.boost += (v.targetBoost - v.boost) * response;
            double mx = (v.ax + v.bx) * .5 - cx, my = (v.ay + v.by) * .5 - cy, mz = (v.az + v.bz) * .5 - cz;
            double distance = Math.sqrt(mx * mx + my * my + mz * mz);
            double opacity = v.alpha * TrailMath.fade(distance, range, FADE_START_FRACTION) * (1 - v.visited * (1 - VISITED_OPACITY));
            if (opacity < .003 || !frustum.testSphere((float)mx, (float)my, (float)mz, 2)) continue;
            double pulse = module.showDirection.get() ? Math.pow(Math.max(0, Math.sin(v.along * 1.8 - seconds * 3)), 6) : 0;
            opacity *= (.65 + .25 * pulse + .1 * v.boost)*(v.plugged?.38:1);
            int color = GradientSetting.mix(accent, 0xffffffff, .3 * v.boost);
            float ax = (float)(v.ax - cx), ay = (float)(v.ay - cy), az = (float)(v.az - cz);
            float bx = (float)(v.bx - cx), by = (float)(v.by - cy), bz = (float)(v.bz - cz);
            geometry.line(ax, ay, az, bx, by, bz, alpha(color, opacity * .08), 10);
            geometry.line(ax, ay, az, bx, by, bz, alpha(color, opacity * .23), 4.5f);
            geometry.line(ax, ay, az, bx, by, bz, alpha(color, opacity), (float)(1.4 + v.boost * .8));
        }
        for (Marker v : markers) {
            boolean visible = module.enabled() && v.wanted && module.smart();
            v.alpha += ((visible ? 1 : 0) - v.alpha) * response;
            long key = v.data.key();
            double x = TrailMath.x(key) + .5 - cx, y = TrailMath.y(key) + .5 - cy, z = TrailMath.z(key) + .5 - cz;
            double opacity = v.alpha * TrailMath.fade(Math.sqrt(x * x + y * y + z * z), range, FADE_START_FRACTION);
            if (module.dimVisited.get() && v.visited) opacity *= .4;
            if (opacity < .003 || !frustum.testSphere((float)x, (float)y, (float)z, 1)) continue;
            marker((float)x, (float)y, (float)z, v.data.kind(), alpha(accent, opacity), seconds);
        }
        if(TrailTimings.ENABLED)extractionNanos=System.nanoTime()-timing;
    }
    private void marker(float x, float y, float z, TunnelNetwork.Kind kind, int color, double seconds) {
        float size = kind == TunnelNetwork.Kind.ROOM ? .5f : .28f;
        y += (float)(Math.sin(seconds * 1.8) * .06);
        // Room: double diamond; branches: cross; ends: diamond; hazards: exclamation stroke.
        if (kind.hazard()) {
            line(x, y + .4f, z, x, y - .05f, z, color);
            line(x, y - .2f, z, x, y - .27f, z, color);
            // A horizontal foot stays visible when looking straight down a shaft.
            line(x - .12f, y - .27f, z, x + .12f, y - .27f, z, color);
            line(x, y - .27f, z - .12f, x, y - .27f, z + .12f, color);
        } else if(kind==TunnelNetwork.Kind.PLUG) {
            line(x-size,y-size,z,x-size,y+size,z,color);
            line(x+size,y-size,z,x+size,y+size,z,color);
            line(x-size,y,z,x+size,y,z,color);
        } else if (kind == TunnelNetwork.Kind.JUNCTION) {
            line(x - size, y, z, x + size, y, z, color); line(x, y, z - size, x, y, z + size, color);
            line(x, y - size, z, x, y + size, z, color);
        } else {
            line(x - size, y, z, x, y + size, z, color); line(x, y + size, z, x + size, y, z, color);
            line(x + size, y, z, x, y - size, z, color); line(x, y - size, z, x - size, y, z, color);
            if (kind == TunnelNetwork.Kind.ROOM) {
                line(x, y + size, z, x, y, z + size, color); line(x, y, z + size, x, y - size, z, color);
            }
        }
    }
    private void line(float ax, float ay, float az, float bx, float by, float bz, int color) {
        geometry.line(ax, ay, az, bx, by, bz, alpha(color, .12), 8);
        geometry.line(ax, ay, az, bx, by, bz, color, 2);
    }
    public void draw(WorldRenderContext context) {
        long timing=TrailTimings.start();geometry.draw(context);
        if(TrailTimings.ENABLED)TrailTimings.record(TrailTimings.RENDER,extractionNanos+System.nanoTime()-timing);
    }
    private void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != module.world() || mc.options.hideGui || screen == null || (hintAlpha < .003 && !nametags.visible())) return;
        if (mc.screen == null && ScrimmyClient.THEME.blur.get() >= 1) blur.capture(mc, ScrimmyClient.THEME.blur.get().intValue());
        else blur.close();
        nametags.draw(g,mc,screen,screenWidth,screenHeight,blur);
        if(hintAlpha<.003||hint==null)return;
        hintWidth = ClientFonts.width(hint) + 22;
        int width = Math.min(hintWidth, screenWidth - 16), accent = ScrimmyClient.THEME.accent.get();
        int tint = ThemeSurfaces.fill();
        g.pose().pushMatrix(); g.pose().translate(screenWidth / 2f, screenHeight - 65 + (float)((1 - hintAlpha) * 8));
        panel.draw(g, width, 23, Math.min(11, ScrimmyClient.THEME.radius.get().intValue() + 3), alpha(tint, hintAlpha),
                alpha(accent, hintAlpha * .45), alpha(accent, hintAlpha * .13), screen, blur.texture());
        g.pose().translate(0, 7);
        float textScale = Math.min(1, (width - 22f) / Math.max(1, hintWidth - 22f));
        g.pose().scale(textScale, textScale);
        ClientFonts.draw(g, hint, -hintWidth / 2 + 11, 0, alpha(0xffeaf3ff, hintAlpha));
        g.pose().popMatrix();
    }
    private static int alpha(int color, double factor) { return (int)Math.min(255, (color >>> 24) * Math.max(0, factor)) << 24 | (color & 0xffffff); }
    @Override public void close() { clear(); geometry.close(); }
}
