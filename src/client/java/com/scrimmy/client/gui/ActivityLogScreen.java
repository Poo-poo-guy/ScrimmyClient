package com.scrimmy.client.gui;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.activity.ActivityEntry;
import com.scrimmy.client.module.basefinding.ActivityLog;
import com.scrimmy.client.module.basefinding.SoundFinder;
import com.scrimmy.client.render.*;
import com.scrimmy.client.sound.SoundTagAnimation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Draggable, resizable glass history; all row text and filtering is prepared on ticks. */
public final class ActivityLogScreen extends Screen {
    private final ActivityLog module;
    private final Screen parent;
    private final PanelBlur blur=new PanelBlur();
    private final SoundTagAnimation animation=new SoundTagAnimation();
    private final List<Row> rows=new ArrayList<>();
    private final String[] controls=new String[9];
    private EditBox search;
    private String selected, category="All", dimension="All";
    private int severity=-1, page;
    private boolean below, byScore, dragging, resizing;
    private int x,y,w,h;
    private long frame;
    private ScreenRectangle screen;
    private String status="", notice="";
    private long noticeUntil;
    private record Row(ActivityEntry entry, ClientFonts.RetainedLabel[] lines) {}
    public ActivityLogScreen(ActivityLog module,Screen parent) { super(Component.literal("ActivityLog")); this.module=module;this.parent=parent; }
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init() {
        screen=new ScreenRectangle(0,0,width,height);
        String value=search==null?"":search.getValue();
        x=module.panelX.get().intValue();y=module.panelY.get().intValue();w=module.panelWidth.get().intValue();h=module.panelHeight.get().intValue(); clamp();
        search=new EditBox(ClientFonts.widgetFont(),x+12,y+32,w-24,20,Component.literal("Search player name"));
        search.setHint(ClientFonts.component("Search player name")); search.setMaxLength(64); search.setValue(value);
        search.setResponder(s->{page=0;reload();}); addRenderableWidget(search); reload();
    }
    private void clamp(){w=Math.min(w,width-8);h=Math.min(h,height-8);x=Math.clamp(x,4,Math.max(4,width-w-4));y=Math.clamp(y,4,Math.max(4,height-h-4));}
    private int rowCount(){return Math.max(1,(h-142)/44);}
    @Override public void tick(){reload();}
    private void reload() {
        if(search==null)return;
        String query=search.getValue().strip().toLowerCase(Locale.ROOT);
        var entries=module.entries().stream().filter(e->category.equals("All")||e.category().equals(category))
                .filter(e->dimension.equals("All")||e.dimension().equals(dimension))
                .filter(e->module.severity(e.score())>=Math.max(module.minimum(),severity))
                .filter(e->!below||e.underground())
                .filter(e->query.isEmpty()||e.category().equals("Player")&&playerName(e).toLowerCase(Locale.ROOT).contains(query))
                .sorted(byScore?Comparator.comparingDouble(ActivityEntry::score).reversed().thenComparing(Comparator.comparingLong(ActivityEntry::lastSeen).reversed()):Comparator.comparingLong(ActivityEntry::lastSeen).reversed()).toList();
        page=Math.clamp(page,0,Math.max(0,(entries.size()-1)/rowCount()));rows.clear();
        long now=System.currentTimeMillis();
        for(int i=page*rowCount();i<Math.min(entries.size(),(page+1)*rowCount());i++) {
            ActivityEntry e=entries.get(i);
            String distance=minecraft.player!=null&&minecraft.level!=null&&e.dimension().equals(minecraft.level.dimension().identifier().toString())
                    ?Math.round(Math.sqrt(e.distanceSquared(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ())))+"m":"other dimension";
            String[] lines={e.category()+" · "+SoundFinder.label(module.severity(e.score()))+" · "+e.hits()+" hits"+(e.underground()?" · underground":""),
                    e.details(),e.coordinates()+" · "+e.dimension()+" · "+distance+" · "+ago(now-e.lastSeen())+" · session "+e.session().substring(0,Math.min(8,e.session().length()))};
            ClientFonts.RetainedLabel[] labels=new ClientFonts.RetainedLabel[3];
            for(int j=0;j<3;j++)labels[j]=ClientFonts.retain(lines[j],false);
            rows.add(new Row(e,labels));
        }
        controls[0]="Category: "+category;controls[1]="Severity: "+(severity<0?"All":SoundFinder.label(severity));
        controls[2]="Dimension: "+dimension.replace("minecraft:","");controls[3]="Underground: "+(below?"Yes":"Any");
        controls[4]="Sort: "+(byScore?"Score":"Recent");controls[5]="Clear all";controls[6]="Delete selected";controls[7]="Previous";controls[8]="Next";
        status=now<noticeUntil?notice:!module.error().isEmpty()?module.error():!module.ready()?"Join a world to load its saved activity":entries.size()+" entries · page "+(page+1)+" · click row: local waypoint / copy other dimension";
    }
    private static String playerName(ActivityEntry e) { int end=e.details().indexOf(" · ");return end<0?e.details():e.details().substring(0,end); }
    public static String ago(long age){long seconds=Math.max(0,age/1000);return seconds<60?seconds+"s ago":seconds<3600?seconds/60+"m ago":seconds<86400?seconds/3600+"h ago":seconds/86400+"d ago";}
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float delta){if(ScrimmyClient.THEME.blur.get()>=1)blur.capture(minecraft);else blur.close();}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        long now=System.nanoTime();animation.step(true,false,frame==0?0:(now-frame)/1e9,.2,.25,0);frame=now;
        int tint=alpha(ThemeSurfaces.fill()),accent=alpha(ScrimmyClient.THEME.accent.get());
        if(blur.texture()!=null)RoundedRenderer.glass(g,x,y,w,h,10,alpha(0xffffffff),blur.texture());
        RoundedRenderer.panel(g,x,y,w,h,10,tint,accent,.2f,.5f);
        ClientFonts.draw(g,"ActivityLog · drag title to move",x+12,y+12,accent);
        ClientFonts.draw(g,"Esc to return",x+w-100,y+12,alpha(0xffa9bdcc));
        for(int i=0;i<5;i++)button(g,i,x+12+i*(w-24)/5,y+58,(w-24)/5-3,mx,my);
        g.enableScissor(x+10,y+88,x+w-10,y+h-50);
        for(int i=0;i<rows.size();i++) {
            Row row=rows.get(i); int ry=y+88+i*44;
            RoundedRenderer.panel(g,x+10,ry,w-20,41,5,alpha(row.entry.id().equals(selected)?0x88648395:0x333e586c),alpha(0x228da9bc),0,0);
            for(int j=0;j<3;j++)row.lines[j].draw(g,x+16,ry+4+j*12,alpha(j==0?module.color(module.severity(row.entry.score())):0xffc4d3e3),screen);
        }
        g.disableScissor();
        g.enableScissor(x+10,y+h-48,x+w-10,y+h-33);ClientFonts.draw(g,status,x+12,y+h-46,alpha(0xffa9bdcc));g.disableScissor();
        for(int i=5;i<9;i++)button(g,i,x+12+(i-5)*(w-24)/4,y+h-28,(w-24)/4-3,mx,my);
        ClientFonts.draw(g,"◢",x+w-10,y+h-10,accent);super.render(g,mx,my,delta);
    }
    private int alpha(int color){return ((int)((color>>>24)*animation.alpha())<<24)|(color&0xffffff);}
    private void button(GuiGraphics g,int index,int bx,int by,int bw,int mx,int my){
        boolean hover=mx>=bx&&mx<bx+bw&&my>=by&&my<by+22;
        RoundedRenderer.panel(g,bx,by,bw,22,5,alpha(hover?0x99648395:0x55557183),alpha(0x338da9bc),0,0);
        g.enableScissor(bx+3,by,bx+bw-3,by+22);ClientFonts.draw(g,controls[index],bx+5,by+7,alpha(0xffedf6ff));g.disableScissor();
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice){
        if(e.button()!=0)return super.mouseClicked(e,twice);
        if(e.x()>=x+w-14&&e.x()<=x+w&&e.y()>=y+h-14&&e.y()<=y+h){resizing=true;return true;}
        if(e.x()>=x&&e.x()<=x+w&&e.y()>=y&&e.y()<y+28){dragging=true;return true;}
        if(e.x()>=x+10&&e.x()<x+w-10&&e.y()>=y+88&&e.y()<y+h-50){
            int i=((int)e.y()-y-88)/44;
            if(i<rows.size()){var entry=rows.get(i).entry;selected=entry.id();notice=module.activate(entry);noticeUntil=System.currentTimeMillis()+2000;reload();return true;}
        }
        for(int i=0;i<9;i++){
            int bx=x+12+(i<5?i*(w-24)/5:(i-5)*(w-24)/4),by=i<5?y+58:y+h-28,bw=i<5?(w-24)/5-3:(w-24)/4-3;
            if(e.x()<bx||e.x()>=bx+bw||e.y()<by||e.y()>=by+22)continue;
            switch(i){
                case 0->{List<String> options=new ArrayList<>(List.of("All"));options.addAll(List.of(ActivityLog.CATEGORIES));category=options.get((options.indexOf(category)+1)%options.size());page=0;}
                case 1->{severity=severity==2?-1:severity+1;page=0;}
                case 2->{List<String> options=new ArrayList<>(List.of("All"));module.entries().stream().map(ActivityEntry::dimension).distinct().sorted().forEach(options::add);dimension=options.get((options.indexOf(dimension)+1)%options.size());page=0;}
                case 3->{below=!below;page=0;}
                case 4->{byScore=!byScore;page=0;}
                case 5->module.clearEntries();
                case 6->{if(selected!=null){module.remove(selected);selected=null;}}
                case 7->page=Math.max(0,page-1);
                case 8->page++;
            }reload();return true;
        }
        return super.mouseClicked(e,twice);
    }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){
        if(dragging||resizing){
            if(dragging){x+=(int)dx;y+=(int)dy;}else{w=Math.max(380,w+(int)dx);h=Math.max(240,h+(int)dy);}
            clamp();search.setX(x+12);search.setY(y+32);search.setWidth(w-24);reload();return true;
        }return super.mouseDragged(e,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e){if(dragging||resizing){dragging=resizing=false;savePosition();return true;}return super.mouseReleased(e);}
    private void savePosition(){module.panelX.set((double)x);module.panelY.set((double)y);module.panelWidth.set((double)w);module.panelHeight.set((double)h);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void removed(){savePosition();blur.close();}
}
