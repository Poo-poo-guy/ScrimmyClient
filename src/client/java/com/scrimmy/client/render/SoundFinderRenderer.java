package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.sound.SoundTracker;
import com.scrimmy.client.sound.SoundMath;
import com.scrimmy.client.sound.SoundTagAnimation;
import com.scrimmy.client.sound.SoundTagSelection;
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
    private final float[] corners = new float[24];
    private float[] placedBounds = new float[32 * 4];
    private final SoundTagSelection<Visual> selection = new SoundTagSelection<>();
    private final Map<SoundTracker.Source, Visual> cache = new IdentityHashMap<>();
    private Visual[] visuals = new Visual[0];
    private SoundTracker.Chunk[] chunks = new SoundTracker.Chunk[0];
    private Visual[] projected = new Visual[32];
    private int projectedCount, screenWidth, screenHeight;
    private long lastFrame;
    private ScreenRectangle screen;
    private static final class Visual {
        final SoundTracker.Source source;
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
        final SoundTagAnimation animation = new SoundTagAnimation();
        final String[] lines = new String[SoundTagAnimation.LINES];
        final ClientFonts.RetainedLabel[] text = new ClientFonts.RetainedLabel[SoundTagAnimation.LINES];
        final float[] widths = new float[SoundTagAnimation.LINES];
        int width, severity, color, fontRevision = -1;
        double x, y, z, opacity, tagOpacity, distance;
        float screenX, screenY, scale;
        boolean eligible, inRange, lookTag;
        Visual(SoundTracker.Source source) { this.source = source; }
        void line(int index, String value, boolean reload) {
            if (reload || !value.equals(lines[index])) {
                lines[index] = value; text[index] = ClientFonts.retain(value, false);
            }
        }
    }
    public SoundFinderRenderer(SoundFinder module) { this.module = module; }
    public void initialize() {
        // 1024 sources (72 vertices/box) plus 1024 chunk rectangles (16 vertices each).
        geometry.reserve(1024 * 88);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient", "sound_finder"), (g, delta) -> hud(g));
    }
    public void clear() {
        cache.clear(); selection.clear(); visuals = new Visual[0]; chunks = new SoundTracker.Chunk[0];
        Arrays.fill(projected, null); projectedCount = 0; lastFrame = 0; geometry.clear(); blur.close();
    }
    public void refresh(Minecraft mc, long now) {
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (screen == null || screenWidth != w || screenHeight != h) {
            screenWidth = w; screenHeight = h; screen = new ScreenRectangle(0, 0, w, h);
        }
        Set<SoundTracker.Source> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(module.tracker.blocks()); live.addAll(module.tracker.entities());
        // An expired/filtered outgoing source keeps its own panel/timeline until the exit finishes.
        cache.values().removeIf(v -> !live.contains(v.source) && v.animation.progress() == 0);
        for (Visual v : cache.values()) v.eligible = false;
        var camera = mc.gameRenderer.getMainCamera().position();
        double range = module.maxDistance.get();
        int revision = ClientFonts.revision();
        for (var s : live) {
            int severity = module.severity(s.score);
            double x = s.x, y = s.y, z = s.z;
            double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || severity < module.minimum()) continue;
            Visual v = cache.computeIfAbsent(s, Visual::new);
            v.eligible = true;
            v.x = x; v.y = y; v.z = z; v.distance = distance; v.severity = severity;
            v.color = module.color(severity); v.opacity = s.opacity;
            boolean reload = v.fontRevision != revision;
            v.line(0, SoundFinder.label(severity) + (s.entity ? " · Entity marker" : " · " + (s.confirmed ? "confirmed" : "unconfirmed")), reload);
            v.line(1, s.soundId, reload);
            v.line(2, s.blockId + " · " + s.category, reload);
            v.line(3, s.hits + " times heard · " + Math.max(0, (now - s.lastHeard) / 1000) + "s ago · " + Math.round(distance) + "m", reload);
            v.line(4, s.pos.getX() + ", " + s.pos.getY() + ", " + s.pos.getZ(), reload);
            v.fontRevision = revision;
        }
        for (Visual v : cache.values()) if (v.fontRevision != revision) {
            for (int line = 0; line < v.lines.length; line++) v.line(line, v.lines[line], true);
            v.fontRevision = revision;
        }
        visuals = cache.values().toArray(Visual[]::new);
        Arrays.sort(visuals, Comparator.comparingDouble(v -> v.distance));
        // Capacity changes happen only on ticks, including room for tags still fading out.
        if (projected.length < visuals.length) {
            projected = new Visual[visuals.length]; placedBounds = new float[visuals.length * 4];
        }
        chunks = module.tracker.chunks().toArray(SoundTracker.Chunk[]::new);
    }
    public void extract(WorldExtractionContext context) {
        Arrays.fill(projected, 0, projectedCount, null); projectedCount = 0; geometry.clear();
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || mc.level == null || module.world() != mc.level || mc.player == null) return;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.max(0, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        view.set(context.viewMatrix()); geometry.begin(view); projection.set(context.cullProjectionMatrix()).mul(view);
        var camera = context.worldState().cameraRenderState;
        double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z, range = module.maxDistance.get();
        // Use the camera quaternion (including Freecam/third person), never the player's block raycast.
        var look = mc.gameRenderer.getMainCamera().forwardVector();
        boolean tagsEnabled = !module.nametagMode.get().equals("Off");
        selection.begin(module.angle.get(), module.hysteresisMargin.get());
        for (Visual v : visuals) {
            double dx = v.x - cx, dy = v.y - cy, dz = v.z - cz, distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            v.inRange = distance <= range && distance >= .001;
            if (!v.inRange || !v.eligible) continue;
            if (tagsEnabled) selection.consider(v, distance, (dx * look.x() + dy * look.y() + dz * look.z()) / distance);
            double opacity = v.opacity * distanceFade(distance, range);
            if (opacity < .002) continue;
            float x = (float)dx, y = (float)dy, z = (float)dz;
            float half = v.source.entity ? .17f : .503f;
            box(x - half, y - half, z - half, half * 2, alpha(v.color, opacity), module.softFill.get() && !v.source.entity);
        }
        selection.finish();
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
        boolean redMode = module.nametagMode.get().equals("Look-at plus always show Red flags");
        double in = module.animationIn.get() / 1000, out = module.animationOut.get() / 1000, linger = module.lingerTime.get() / 1000;
        int redCount = 0;
        Visual target = selection.active();
        for (Visual v : visuals) {
            boolean alwaysRed = redMode && v.eligible && v.inRange && v.severity == 2 && redCount++ < 32;
            boolean wanted = selection.wanted(v) || alwaysRed;
            v.lookTag = v == target || v.lookTag && !wanted;
            v.animation.step(wanted, selection.mayLinger(v), dt, in, out, linger);
            if (v == target && v.animation.hiding()) selection.release(v);
            if (v.animation.progress() == 0) v.lookTag = false;
        }
        // Outgoing look tags render under the active one; both survive overlap during a crossfade.
        for (Visual v : visuals) if (v != target && v.lookTag && v.animation.progress() > 0) project(v, cx, cy, cz, range);
        if (target != null && target.animation.progress() > 0) project(target, cx, cy, cz, range);
        for (Visual v : visuals) if (v != target && !v.lookTag && v.animation.progress() > 0) project(v, cx, cy, cz, range);
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
        v.tagOpacity = v.opacity * distanceFade(distance, range) * v.animation.alpha();
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
        SoundTagAnimation.Style style = SoundTagAnimation.style(module.animationStyle.get());
        for (int i=0;i<projectedCount;i++) {
            Visual v=projected[i];
            if (v.fontRevision != ClientFonts.revision()) continue;
            v.width = 0;
            for (int line=0;line<v.lines.length;line++) {
                v.widths[line] = v.text[line].width();
                v.width = Math.max(v.width, (int)Math.ceil(v.widths[line]));
            }
            v.width += 20;
            float animationX = (float)v.animation.scaleX(style), animationY = (float)v.animation.scaleY(style);
            float baseScale = Math.min(v.scale, (screenWidth - 16f) / (v.width * Math.max(1, animationX)));
            float scaleX = baseScale * animationX, scaleY = baseScale * animationY;
            float half=v.width*scaleX/2, height=66*scaleY;
            float x=Math.clamp((v.screenX+1)*screenWidth/2,half+4,screenWidth-half-4);
            float y=Math.clamp((1-v.screenY)*screenHeight/2 + (float)v.animation.slide(style) + (66*baseScale-height)/2,
                    4,Math.max(4,screenHeight-height-4));
            boolean overlap=false;
            if (!v.lookTag) for(int j=0;j<placed;j++){int b=j*4;if(x+half>=placedBounds[b]&&x-half<=placedBounds[b+2]&&y+height>=placedBounds[b+1]&&y<=placedBounds[b+3]){overlap=true;break;}}
            if(overlap)continue;
            int b=placed++*4;placedBounds[b]=x-half;placedBounds[b+1]=y;placedBounds[b+2]=x+half;placedBounds[b+3]=y+height;
            int background=ThemeSurfaces.fill();
            double glow = module.redFlagPulse.get() && v.severity == 2 ? v.animation.pulse() : 1;
            int shadow = module.redFlagPulse.get() && v.severity == 2 ? (v.color & 0xffffff) | 0x40000000 : 0x40000000;
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scaleX,scaleY);
            v.panel.draw(g,v.width,66,Math.min(15,ScrimmyClient.THEME.radius.get().intValue()+4),
                    alpha(background,v.tagOpacity),alpha(v.color,v.tagOpacity*.4*glow),alpha(shadow,v.tagOpacity*glow),screen,blur.texture());
            for(int line=0;line<v.lines.length;line++)v.text[line].draw(g,-v.widths[line]/2,6+line*12,
                    alpha(line==0?v.color:0xffc4d3e3,v.tagOpacity*v.animation.lineAlpha(line)),screen);
            g.pose().popMatrix();
        }
    }
    public void draw(WorldRenderContext context) { if (module.enabled()) geometry.draw(context, module.throughWalls.get()); }
    private static int alpha(int color,double factor){return ((int)Math.min(255,(color>>>24)*Math.max(0,factor))<<24)|(color&0xffffff);}
    @Override public void close() { clear(); geometry.close(); }
}
