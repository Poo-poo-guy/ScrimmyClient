package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.sound.SoundTracker;
import com.scrimmy.client.sound.SoundMath;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.*;

/** Tick-cached labels and evidence, rendered with the client's overlay and glass primitives. */
public final class SoundFinderRenderer implements AutoCloseable {
    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};
    private static final int[][] FACES = {{0,2,3,1},{4,5,7,6},{0,1,5,4},{2,6,7,3},{0,4,6,2},{1,3,7,5}};
    private final SoundFinder module;
    private final WorldOverlayRenderer geometry = new WorldOverlayRenderer("Scrimmy SoundFinder");
    private final PanelBlur blur = new PanelBlur();
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private final float[] corners = new float[24], placedBounds = new float[32 * 4];
    private final Map<SoundTracker.Source, Visual> cache = new IdentityHashMap<>();
    private Visual[] visuals = new Visual[0];
    private SoundTracker.Chunk[] chunks = new SoundTracker.Chunk[0];
    private final Visual[] projected = new Visual[32];
    private int projectedCount, screenWidth, screenHeight;
    private ScreenRectangle screen;
    private static final class Visual {
        final SoundTracker.Source source;
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
        final String[] lines = new String[5];
        final int[] widths = new int[5];
        int width, severity, color;
        double x, y, z, opacity, tagOpacity, distance;
        float screenX, screenY, scale;
        Visual(SoundTracker.Source source) { this.source = source; }
    }
    public SoundFinderRenderer(SoundFinder module) { this.module = module; }
    public void initialize() {
        // 1024 sources (72 vertices/box) plus 1024 chunk rectangles (16 vertices each).
        geometry.reserve(1024 * 88);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "sound_finder"), (g, delta) -> hud(g));
    }
    public void clear() { cache.clear(); visuals = new Visual[0]; chunks = new SoundTracker.Chunk[0]; projectedCount = 0; geometry.clear(); blur.close(); }
    public void refresh(Minecraft mc, long now) {
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (screen == null || screenWidth != w || screenHeight != h) {
            screenWidth = w; screenHeight = h; screen = new ScreenRectangle(0, 0, w, h);
        }
        Set<SoundTracker.Source> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(module.tracker.blocks()); live.addAll(module.tracker.entities());
        cache.keySet().retainAll(live);
        var camera = mc.gameRenderer.getMainCamera().position();
        double range = module.maxDistance.get();
        List<Visual> visible = new ArrayList<>();
        for (var s : live) {
            int severity = module.severity(s.score);
            double x = s.x, y = s.y, z = s.z;
            double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || severity < module.minimum()) continue;
            Visual v = cache.computeIfAbsent(s, Visual::new);
            v.x = x; v.y = y; v.z = z; v.distance = distance; v.severity = severity;
            v.color = module.color(severity); v.opacity = s.opacity;
            v.lines[0] = SoundFinder.label(severity) + (s.entity ? " · Entity marker" : " · " + (s.confirmed ? "confirmed" : "unconfirmed"));
            v.lines[1] = s.soundId;
            v.lines[2] = s.blockId + " · " + s.category;
            v.lines[3] = s.hits + " times heard · " + Math.max(0, (now - s.lastHeard) / 1000) + "s ago · " + Math.round(distance) + "m";
            v.lines[4] = s.pos.getX() + ", " + s.pos.getY() + ", " + s.pos.getZ();
            v.width = 0;
            for (int i = 0; i < v.lines.length; i++) { v.widths[i] = ClientFonts.width(v.lines[i]); v.width = Math.max(v.width, v.widths[i]); }
            v.width += 20; visible.add(v);
        }
        visible.sort(Comparator.comparingDouble(v -> v.distance));
        visuals = visible.toArray(Visual[]::new);
        chunks = module.tracker.chunks().toArray(SoundTracker.Chunk[]::new);
    }
    public void extract(WorldExtractionContext context) {
        projectedCount = 0; geometry.clear();
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || mc.level == null || module.world() != mc.level || mc.player == null) return;
        view.set(context.viewMatrix()); geometry.begin(view); projection.set(context.cullProjectionMatrix()).mul(view);
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z, range = module.maxDistance.get();
        // Use the camera quaternion (including Freecam/third person), never the player's block raycast.
        var look = mc.gameRenderer.getMainCamera().forwardVector();
        Visual target = null;
        double nearest = Double.MAX_VALUE, cosine = Math.cos(Math.toRadians(module.angle.get()));
        for (Visual v : visuals) {
            double dx = v.x - cx, dy = v.y - cy, dz = v.z - cz, distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || distance < .001) continue;
            if (distance < nearest && SoundMath.insideCone(dx, dy, dz, look.x(), look.y(), look.z(), cosine)) { target = v; nearest = distance; }
            double opacity = v.opacity * distanceFade(distance, range);
            if (opacity < .002) continue;
            float x = (float)dx, y = (float)dy, z = (float)dz;
            float half = v.source.entity ? .17f : .503f;
            box(x - half, y - half, z - half, half * 2, alpha(v.color, opacity), module.softFill.get() && !v.source.entity);
        }
        if (module.chunkHighlight.get()) for (SoundTracker.Chunk c : chunks) {
            int severity = module.severity(c.score);
            double dx = c.x * 16 + 8 - cx, dz = c.z * 16 + 8 - cz, dy = module.chunkHeight.get() - cy;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || severity < module.minimum()) continue;
            int color = alpha(module.color(severity), c.opacity * distanceFade(distance, range));
            float x = (float)(c.x * 16 - cx), z = (float)(c.z * 16 - cz), y = (float)dy;
            geometry.line(x,y,z,x+16,y,z,color,1.5f); geometry.line(x+16,y,z,x+16,y,z+16,color,1.5f);
            geometry.line(x+16,y,z+16,x,y,z+16,color,1.5f); geometry.line(x,y,z+16,x,y,z,color,1.5f);
        }
        if (module.nametagMode.get().equals("Off")) return;
        // Project the selected tag first, then the nearest red flags up to a fixed HUD limit.
        if (target != null) project(target, cx, cy, cz, range);
        if (module.nametagMode.get().equals("Look-at plus always show Red flags"))
            for (Visual v : visuals) if (v != target && v.severity == 2 && projectedCount < projected.length) project(v, cx, cy, cz, range);
    }
    private double distanceFade(double distance, double range) {
        return SoundMath.distanceFade(distance, range, module.distanceFade.get());
    }
    private void project(Visual v, double cx, double cy, double cz, double range) {
        double dx = v.x - cx, dy = v.y - cy, dz = v.z - cz, distance = Math.sqrt(dx*dx+dy*dy+dz*dz);
        if (distance > range) return;
        clip.set((float)dx, (float)(dy + .65), (float)dz, 1); projection.transform(clip);
        if (clip.w <= .01 || Math.abs(clip.x) >= clip.w || Math.abs(clip.y) >= clip.w) return;
        v.screenX = clip.x / clip.w; v.screenY = clip.y / clip.w;
        v.scale = (float)Math.clamp(64 / Math.max(1, distance), .65, 1.15);
        v.tagOpacity = v.source.opacity * distanceFade(distance, range);
        projected[projectedCount++] = v;
    }
    private void box(float x, float y, float z, float size, int color, boolean fill) {
        for (int k = 0; k < 8; k++) {
            corners[k*3] = x + ((k&1)==0?0:size); corners[k*3+1] = y + ((k&2)==0?0:size); corners[k*3+2] = z + ((k&4)==0?0:size);
        }
        if (fill) for (int[] face : FACES) for (int k : face) geometry.vertex(corners[k*3],corners[k*3+1],corners[k*3+2],alpha(color,.10),0,0,0,0,0);
        for (int[] edge : EDGES) { int a=edge[0]*3,b=edge[1]*3; geometry.line(corners[a],corners[a+1],corners[a+2],corners[b],corners[b+1],corners[b+2],color,1.5f); }
    }
    private void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || mc.level == null || mc.level != module.world() || mc.options.hideGui || screen == null || projectedCount == 0) return;
        if (ScrimmyClient.THEME.blur.get() >= 1 && mc.screen == null) blur.capture(mc, ScrimmyClient.THEME.blur.get().intValue()); else blur.close();
        int placed = 0;
        for (int i=0;i<projectedCount;i++) {
            Visual v=projected[i]; if (v.tagOpacity < .01) continue;
            v.width = 0;
            for (int line=0;line<v.lines.length;line++) {
                v.widths[line] = ClientFonts.width(v.lines[line]);
                v.width = Math.max(v.width, v.widths[line]);
            }
            v.width += 20;
            float scale = Math.min(v.scale, (screenWidth - 16f) / v.width);
            float half=v.width*scale/2, height=66*scale;
            float x=Math.clamp((v.screenX+1)*screenWidth/2,half+4,screenWidth-half-4);
            float y=Math.clamp((1-v.screenY)*screenHeight/2,4,Math.max(4,screenHeight-height-4));
            boolean overlap=false;
            for(int j=0;j<placed;j++){int b=j*4;if(x+half>=placedBounds[b]&&x-half<=placedBounds[b+2]&&y+height>=placedBounds[b+1]&&y<=placedBounds[b+3]){overlap=true;break;}}
            if(overlap)continue;
            int b=placed++*4;placedBounds[b]=x-half;placedBounds[b+1]=y;placedBounds[b+2]=x+half;placedBounds[b+3]=y+height;
            int background=ThemeSurfaces.fill();
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scale,scale);
            v.panel.draw(g,v.width,66,Math.min(15,ScrimmyClient.THEME.radius.get().intValue()+4),
                    alpha(background,v.tagOpacity),alpha(v.color,v.tagOpacity*.4),alpha(0x40000000,v.tagOpacity),screen,blur.texture());
            for(int line=0;line<v.lines.length;line++)ClientFonts.draw(g,v.lines[line],-ClientFonts.widthExact(v.lines[line])/2f,6+line*12,
                    alpha(line==0?v.color:0xffc4d3e3,v.tagOpacity));
            g.pose().popMatrix();
        }
    }
    public void draw(WorldRenderContext context) { if (module.enabled()) geometry.draw(context, module.throughWalls.get()); }
    private static int alpha(int color,double factor){return ((int)Math.min(255,(color>>>24)*Math.max(0,factor))<<24)|(color&0xffffff);}
    @Override public void close() { clear(); geometry.close(); }
}
