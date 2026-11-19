package com.scrimmy.client.render;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.DrillTrail;
import com.scrimmy.client.setting.GradientSetting;
import com.scrimmy.client.trail.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.*;

/** Chunk Finder's glass pill primitives, projection and overlap policy, with tick-prepared text. */
final class DrillTrailNametags {
    static final int MAX_TAGS=48;
    static final double MIN_SCALE=.75, MAX_SCALE=1.2, FADE_START=.65, INSIDE_TAG_AHEAD=4;
    private final DrillTrail module;
    private final Map<Long,Tag> cache=new HashMap<>();
    private Tag[] tags=new Tag[0];
    private final Vector4f clip=new Vector4f();
    private final float[] bounds=new float[MAX_TAGS*4];
    private static final class Tag {
        final RoundedRenderer.ReusablePanel panel=new RoundedRenderer.ReusablePanel();
        String label="DrillTrail",text;
        int labelWidth,textWidth;
        double x,y,z,distance,length,alpha,opacity;
        float screenX,screenY,scale=.75f;
        boolean wanted,projected;
    }
    DrillTrailNametags(DrillTrail module){this.module=module;}
    void clear(){cache.clear();tags=new Tag[0];}
    void refresh(Minecraft mc) {
        cache.values().removeIf(t->!t.wanted&&t.alpha<.002);
        for(Tag t:cache.values())t.wanted=false;
        if(module.enabled()&&module.nametags.get()) {
            double px=mc.player.getX(),py=mc.player.getY()+.9,pz=mc.player.getZ(),range=module.range.get();
            var look=mc.player.getLookAngle();
            List<Tag> nearest=new ArrayList<>();
            for(var tunnel:module.network().tunnels()) {
                double best=Double.MAX_VALUE,bestHeading=-Double.MAX_VALUE,nx=0,ny=0,nz=0,remaining=0;
                long anchor=0;boolean aheadFrontier=false;
                long[] closestPath=null;double tagAlong=0;
                for(var path:tunnel.paths()) {
                    long[] points=path.points();double along=0,total=path.length();
                    for(int i=1;i<points.length;i++) {
                        long a=points[i-1],b=points[i];
                        double ax=TrailMath.x(a)+.5,ay=TrailMath.y(a)+.5,az=TrailMath.z(a)+.5;
                        double dx=TrailMath.x(b)-TrailMath.x(a),dy=TrailMath.y(b)-TrailMath.y(a),dz=TrailMath.z(b)-TrailMath.z(a);
                        double squared=dx*dx+dy*dy+dz*dz,length=Math.sqrt(squared);
                        double fraction=squared==0?0:Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy+(pz-az)*dz)/squared));
                        double x=ax+fraction*dx,y=ay+fraction*dy,z=az+fraction*dz;
                        double distance=(x-px)*(x-px)+(y-py)*(y-py)+(z-pz)*(z-pz);
                        double alignment=dx*look.x+dy*look.y+dz*look.z;
                        boolean forward=alignment>=0;
                        double ahead=forward?total-along-fraction*length:along+fraction*length;
                        double heading=(ahead>.1?2:0)+Math.abs(alignment)/Math.max(.001,length);
                        // At a junction, equally close paths must follow the player's view.
                        if(distance<best-1e-8 || Math.abs(distance-best)<=1e-8&&heading>bestHeading) {
                            best=distance;bestHeading=heading;nx=x;ny=y;nz=z;anchor=fraction<.5?a:b;
                            remaining=ahead;
                            closestPath=points;
                            tagAlong=Math.max(0,Math.min(total,along+fraction*length+(forward?INSIDE_TAG_AHEAD:-INSIDE_TAG_AHEAD)));
                            aheadFrontier=module.network().frontiers().contains(points[forward?points.length-1:0]);
                        }
                        along+=length;
                    }
                }
                if(best>range*range)continue;
                Tag tag=cache.get(tunnel.id());
                if(tag==null) {
                    if(cache.size()>=MAX_TAGS*2)continue;
                    tag=new Tag();cache.put(tunnel.id(),tag);
                }
                tag.distance=Math.sqrt(best);tag.x=nx;tag.y=ny+.7;tag.z=nz;tag.length=tunnel.length();
                if(best<=TrailMemory.VISIT_RADIUS*TrailMemory.VISIT_RADIUS && closestPath!=null)
                    placeAlong(tag,closestPath,tagAlong);
                tag.label="DrillTrail";
                if(module.smart())for(var marker:module.network().markers())
                    if(marker.key()==anchor&&!marker.kind().hazard()&&marker.kind()!=TunnelNetwork.Kind.FRONTIER) {
                        tag.label="DrillTrail · "+marker.kind().label;break;
                    }
                tag.text=best<=TrailMemory.VISIT_RADIUS*TrailMemory.VISIT_RADIUS
                        ? Math.round(remaining)+(aheadFrontier?"+":"")+" blocks ahead"
                        : Math.round(tunnel.length())+(tunnel.frontier()?"+":"")+" blocks";
                tag.labelWidth=ClientFonts.width(tag.label);tag.textWidth=ClientFonts.width(tag.text);
                nearest.add(tag);
            }
            nearest.sort(Comparator.comparingDouble((Tag t)->t.distance).thenComparingDouble(t->-t.length));
            for(int i=0;i<Math.min(MAX_TAGS,nearest.size());i++)nearest.get(i).wanted=true;
        }
        tags=cache.values().toArray(Tag[]::new);
        Arrays.sort(tags,Comparator.comparingDouble((Tag t)->t.distance).thenComparingDouble(t->-t.length));
    }
    private static void placeAlong(Tag tag,long[] points,double distance) {
        for(int i=1;i<points.length;i++) {
            long a=points[i-1],b=points[i];double length=TrailMath.distance(a,b);
            if(distance>length && i<points.length-1){distance-=length;continue;}
            double t=length==0?0:Math.max(0,Math.min(1,distance/length));
            tag.x=TrailMath.x(a)+.5+t*(TrailMath.x(b)-TrailMath.x(a));
            tag.y=TrailMath.y(a)+1.2+t*(TrailMath.y(b)-TrailMath.y(a));
            tag.z=TrailMath.z(a)+.5+t*(TrailMath.z(b)-TrailMath.z(a));
            return;
        }
    }
    void project(Matrix4f projection,double cx,double cy,double cz,double range,double response) {
        for(Tag t:tags) {
            t.projected=false;
            t.alpha+=((t.wanted&&module.enabled()&&module.nametags.get()?1:0)-t.alpha)*response;
            double dx=t.x-cx,dy=t.y-cy,dz=t.z-cz,distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
            t.opacity=t.alpha*TrailMath.fade(distance,range,FADE_START);
            if(t.opacity<.003)continue;
            clip.set((float)dx,(float)dy,(float)dz,1);projection.transform(clip);
            if(clip.w<=.01||Math.abs(clip.x)>=clip.w||Math.abs(clip.y)>=clip.w)continue;
            t.screenX=clip.x/clip.w;t.screenY=clip.y/clip.w;t.projected=true;
            float target=(float)Math.max(MIN_SCALE,Math.min(MAX_SCALE,64/Math.max(1,distance)));
            t.scale+=(target-t.scale)*(float)response;
        }
    }
    boolean visible(){for(Tag t:tags)if(t.projected)return true;return false;}
    void draw(GuiGraphics g,Minecraft mc,ScreenRectangle screen,int screenWidth,int screenHeight,PanelBlur blur) {
        int placed=0,accent=ScrimmyClient.THEME.accent.get()|0xff000000;
        for(Tag t:tags) {
            if(!t.projected||t.opacity<.01||placed>=MAX_TAGS)continue;
            float x=(t.screenX+1)*screenWidth/2f,y=(1-t.screenY)*screenHeight/2f+(float)((1-t.alpha)*6);
            t.labelWidth=ClientFonts.width(t.label); t.textWidth=ClientFonts.width(t.text);
            int width=Math.max(t.labelWidth,t.textWidth)+16;
            float half=width*t.scale/2,height=30*t.scale;
            boolean overlap=false;
            for(int i=0;i<placed;i++) {
                int b=i*4;
                if(x+half>=bounds[b]&&x-half<=bounds[b+2]&&y+height>=bounds[b+1]&&y<=bounds[b+3]){overlap=true;break;}
            }
            if(overlap||x-half<0||x+half>screenWidth||y<0||y+height>screenHeight)continue;
            int b=placed++*4;bounds[b]=x-half;bounds[b+1]=y;bounds[b+2]=x+half;bounds[b+3]=y+height;
            int tint=ScrimmyClient.THEME.glassTint.get();
            int background=ThemeSurfaces.fill();
            int lengthTint=GradientSetting.mix(0xff819ab7,accent,Math.min(1,t.length/160));
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(t.scale,t.scale);
            t.panel.draw(g,width,30,Math.min(15,ScrimmyClient.THEME.radius.get().intValue()+4),
                    alpha(background,t.opacity),alpha(accent,t.opacity*.25),alpha(0x40000000,t.opacity),screen,blur.texture());
            ClientFonts.draw(g, t.label, -ClientFonts.widthExact(t.label) / 2f,4,alpha(0xffc4d3e3,t.opacity));
            ClientFonts.draw(g, t.text, -ClientFonts.widthExact(t.text) / 2f,17,alpha(lengthTint,t.opacity));
            g.pose().popMatrix();
        }
    }
    private static int alpha(int color,double opacity){return (int)Math.min(255,(color>>>24)*Math.max(0,opacity))<<24|(color&0xffffff);}
}
