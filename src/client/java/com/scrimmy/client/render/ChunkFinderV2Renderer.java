package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.HumanActivityAnalysis.Ranked;
import com.scrimmy.client.module.basefinding.ChunkFinderV2;
import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.setting.GradientSetting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.*;

/** Tick-prepared north-up heatmap, optional chunk outlines, and ChunkFinder-style glass pills. */
public final class ChunkFinderV2Renderer implements AutoCloseable {
    private final ChunkFinderV2 module;
    private final WorldOverlayRenderer geometry = new WorldOverlayRenderer("Scrimmy ChunkFinder V2");
    private final PanelBlur blur = new PanelBlur();
    private final RoundedRenderer.ReusablePanel gridPanel = new RoundedRenderer.ReusablePanel();
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private final Map<Long, Tag> tagCache = new HashMap<>();
    private Tag[] tags = new Tag[0];
    private Ranked[] chunks = new Ranked[0];
    private final float[] bounds = new float[32*4];
    private int centerX, centerZ, radius;
    private long lastFrame;
    private String targetText;
    private DrillTrail.DrillTarget target;
    private static final class Tag {
        final RoundedRenderer.ReusablePanel panel = new RoundedRenderer.ReusablePanel();
        String title, text;
        int width;
        double x, z, score, alpha;
        float sx, sy, scale = .75f;
        boolean projected;
    }
    public ChunkFinderV2Renderer(ChunkFinderV2 module) { this.module = module; }
    public static int color(double score) { return GradientSetting.mix(0xff536f9c,0xffe6bd70,Math.clamp(score/100,0,1)); }
    public void initialize() {
        geometry.reserve(65*65*16+16);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("scrimmyclient","chunk_finder_v2"),(g,delta)->hud(g));
    }
    public void clear() { chunks = new Ranked[0]; tagCache.clear(); tags = new Tag[0]; geometry.clear(); blur.close(); target = null; targetText = null; }
    public void refresh() {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null || mc.level != module.world()) return;
        centerX = mc.player.blockPosition().getX()>>4; centerZ = mc.player.blockPosition().getZ()>>4;
        radius = Math.min(module.radius.get().intValue(),mc.options.renderDistance().get()+1);
        chunks = module.visible().toArray(Ranked[]::new);
        Set<Long> keep = new HashSet<>(); List<Tag> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(chunks.length,module.topN.get().intValue()); i++) {
            Ranked chunk = chunks[i]; keep.add(chunk.key()); Tag tag = tagCache.computeIfAbsent(chunk.key(),key->new Tag());
            tag.title = "ChunkFinder V2"; tag.text = Math.round(chunk.score())+"/100";
            tag.width = Math.max(ClientFonts.width(tag.title),ClientFonts.width(tag.text))+16;
            tag.score = chunk.score(); tag.x = chunk.x()*16+8; tag.z = chunk.z()*16+8; selected.add(tag);
        }
        tagCache.keySet().retainAll(keep); tags = selected.toArray(Tag[]::new);
        var drill = ScrimmyClient.MODULES.find(DrillTrail.class); target = drill == null ? null : drill.drillTarget();
        targetText = target == null ? null : "DrillTrail: "+target.x()+", "+target.z()+"  "+Math.round(Math.hypot(target.x()*16+8-mc.player.getX(),target.z()*16+8-mc.player.getZ()))+"m";
    }
    public void extract(WorldExtractionContext context) {
        geometry.clear(); for (Tag t : tags) t.projected = false;
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || mc.level == null || mc.player == null || mc.level != module.world()) return;
        long now = System.nanoTime(); double dt = lastFrame == 0 ? 1.0/60 : Math.min(.25,(now-lastFrame)/1e9); lastFrame = now;
        double response = ScrimmyClient.THEME.animations.get() ? 1-Math.exp(-dt*10*ScrimmyClient.THEME.animationSpeed.get()) : 1;
        view.set(context.viewMatrix()); geometry.begin(view); projection.set(context.cullProjectionMatrix()).mul(view);
        var camera = context.worldState().cameraRenderState; double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
        double y = mc.player.getY()+.05;
        if (module.outlines.get()) for (Ranked chunk : chunks) square(chunk.x(),chunk.z(),y,cx,cy,cz,color(chunk.score()),1.4f);
        if (target != null) square(target.x(),target.z(),y,cx,cy,cz,0xffffe3a0,3);
        for (Tag tag : tags) {
            tag.alpha += ((module.nametags.get()?1:0)-tag.alpha)*response;
            if (tag.alpha < .01) continue;
            clip.set((float)(tag.x-cx),(float)(y+3-cy),(float)(tag.z-cz),1); projection.transform(clip);
            if (clip.w <= .01 || Math.abs(clip.x) >= clip.w || Math.abs(clip.y) >= clip.w) continue;
            tag.sx = clip.x/clip.w; tag.sy = clip.y/clip.w; tag.projected = true;
            double distance = Math.hypot(tag.x-cx,tag.z-cz);
            float wanted = (float)Math.clamp(64/Math.max(1,distance),.75,1.2);
            tag.scale += (wanted-tag.scale)*(float)response;
        }
    }
    private void square(int chunkX,int chunkZ,double y,double cx,double cy,double cz,int color,float width) {
        float x = (float)(chunkX*16.0-cx), z = (float)(chunkZ*16.0-cz), by = (float)(y-cy);
        geometry.line(x,by,z,x+16,by,z,color,width); geometry.line(x+16,by,z,x+16,by,z+16,color,width);
        geometry.line(x+16,by,z+16,x,by,z+16,color,width); geometry.line(x,by,z+16,x,by,z,color,width);
    }
    private void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!module.enabled() || mc.level == null || mc.player == null || mc.level != module.world() || mc.options.hideGui) return;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        ScreenRectangle screen = new ScreenRectangle(0,0,sw,sh);
        blur.capture(mc,ScrimmyClient.THEME.blur.get().intValue());
        int placed = 0;
        for (Tag tag : tags) {
            if (!tag.projected || tag.alpha < .01) continue;
            float x = (tag.sx+1)*sw/2, y = (1-tag.sy)*sh/2;
            float half = tag.width*tag.scale/2, height = 30*tag.scale;
            boolean overlap = false;
            for (int i = 0; i < placed; i++) {
                int b = i*4;
                if (x+half >= bounds[b] && x-half <= bounds[b+2] && y+height >= bounds[b+1] && y <= bounds[b+3]) { overlap = true; break; }
            }
            if (overlap || x-half < 0 || x+half > sw || y < 0 || y+height > sh) continue;
            int b = placed++*4; bounds[b] = x-half; bounds[b+1] = y; bounds[b+2] = x+half; bounds[b+3] = y+height;
            g.pose().pushMatrix(); g.pose().translate(x,y); g.pose().scale(tag.scale,tag.scale);
            tag.panel.draw(g,tag.width,30,Math.min(15,ScrimmyClient.THEME.radius.get().intValue()+4),alpha(ThemeSurfaces.fill(),tag.alpha),
                    alpha(ScrimmyClient.THEME.accent.get(),tag.alpha*.25),alpha(0x40000000,tag.alpha),screen,blur.texture());
            ClientFonts.draw(g,tag.title,-ClientFonts.widthExact(tag.title)/2,4,alpha(0xffc4d3e3,tag.alpha));
            ClientFonts.draw(g,tag.text,-ClientFonts.widthExact(tag.text)/2,17,alpha(color(tag.score),tag.alpha)); g.pose().popMatrix();
        }
        if (module.grid.get()) {
            int side = 2*radius+1, cell = Math.max(2,Math.min(10,150/side)), size = side*cell;
            int w = Math.max(size+16,158), h = size+51, x = sw-w-8, y = 8;
            g.pose().pushMatrix(); g.pose().translate(x+w/2f,y);
            gridPanel.draw(g,w,h,Math.min(12,ScrimmyClient.THEME.radius.get().intValue()),ThemeSurfaces.fill(),0x6670d6db,0x40000000,screen,blur.texture());
            g.pose().popMatrix();
            ClientFonts.draw(g,"ChunkFinder V2  N",x+8,y+6,0xffc4d3e3);
            int gx = x+(w-size)/2, gy = y+21;
            g.fill(gx,gy,gx+size,gy+size,0x44000000);
            for (Ranked chunk : chunks) {
                int dx = chunk.x()-centerX+radius, dz = chunk.z()-centerZ+radius;
                if (dx<0||dz<0||dx>=side||dz>=side) continue;
                int px = gx+dx*cell, py = gy+dz*cell;
                g.fill(px,py,px+cell-1,py+cell-1,alpha(color(chunk.score()),.3+.7*chunk.score()/100));
            }
            for (int i = 0; i <= side; i++) {
                g.fill(gx+i*cell,gy,gx+i*cell+1,gy+size,0x224f718a);
                g.fill(gx,gy+i*cell,gx+size,gy+i*cell+1,0x224f718a);
            }
            border(g,gx+radius*cell,gy+radius*cell,cell,0xffedf6ff);
            if (target != null && Math.abs(target.x()-centerX)<=radius && Math.abs(target.z()-centerZ)<=radius)
                border(g,gx+(target.x()-centerX+radius)*cell,gy+(target.z()-centerZ+radius)*cell,cell,0xffffe3a0);
            ClientFonts.draw(g,centerX+", "+centerZ+" | "+module.ranked().size()+" scored",x+8,gy+size+5,0xffa9bbcb);
        }
        if (targetText != null) ClientFonts.draw(g,targetText,8,sh-20,0xffffe3a0);
    }
    private void border(GuiGraphics g,int x,int y,int size,int color) {
        g.fill(x,y,x+size,y+1,color); g.fill(x,y+size-1,x+size,y+size,color);
        g.fill(x,y,x+1,y+size,color); g.fill(x+size-1,y,x+size,y+size,color);
    }
    private static int alpha(int color,double opacity) { return ((int)((color>>>24)*Math.clamp(opacity,0,1))<<24)|(color&0xffffff); }
    public void draw(WorldRenderContext context) { geometry.draw(context); }
    @Override public void close() { clear(); geometry.close(); }
}
