package com.scrimmy.client.gui;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.basefinding.HumanActivityAnalysis.Ranked;
import com.scrimmy.client.module.basefinding.*;
import com.scrimmy.client.render.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Full ranked list reached from the Basefinding ClickGUI panel. */
public final class ChunkFinderV2Screen extends Screen {
    private final ChunkFinderV2 module;
    private final Screen parent;
    private final PanelBlur blur = new PanelBlur();
    private List<Ranked> entries = List.of();
    private int page;
    public ChunkFinderV2Screen(ChunkFinderV2 module, Screen parent) {
        super(Component.literal("ChunkFinder V2 ranked chunks")); this.module = module; this.parent = parent;
    }
    @Override public boolean isPauseScreen() { return false; }
    private int left() { return Math.max(8,(width-760)/2); }
    private int panelWidth() { return Math.min(760,width-16); }
    private int rows() { return Math.max(1,(height-166)/36); }
    @Override public void tick() { entries = module.visible(); page = Math.min(page,Math.max(0,(entries.size()-1)/rows())); }
    @Override protected void init() { tick(); }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        blur.capture(minecraft,ScrimmyClient.THEME.blur.get().intValue()); g.fill(0,0,width,height,0x44081018);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        int x = left(), w = panelWidth(), accent = ScrimmyClient.THEME.accent.get();
        if (blur.texture() != null) RoundedRenderer.glass(g,x,12,w,height-24,10,0xffffffff,blur.texture());
        RoundedRenderer.panel(g,x,12,w,height-24,10,ThemeSurfaces.fill(),0x668da9bc,.2f,.5f);
        text(g,"ChunkFinder V2",x+12,23,accent);
        text(g,module.enabled() ? module.ranked().size()+" scored / "+module.loadedCount()+" loaded; scores are activity evidence" : "Enable ChunkFinder V2 in Basefinding to scan",x+12,40,0xffc4d3e3);
        Ranked hovered = null;
        for (int i = 0; i < rows(); i++) {
            int index = page*rows()+i; if (index >= entries.size()) break;
            Ranked row = entries.get(index); int y = 62+i*36;
            RoundedRenderer.rounded(g,x+9,y,w-18,32,5,mx>=x+9&&mx<x+w-9&&my>=y&&my<y+32?0x555f7c91:0x225f7c91);
            rowText(g,row.x()+", "+row.z()+"  "+Math.round(row.score())+"/100",x+16,y+4,w-165,ChunkFinderV2Renderer.color(row.score()));
            rowText(g,"Block "+(row.x()*16+8)+", "+(row.z()*16+8)+"  "+module.markAt(row.key()),x+16,y+18,w-165,0xffa9bbcb);
            button(g,"Send to DrillTrail",x+w-143,y+3,125,26,accent);
            if (mx >= x+9 && mx < x+w-9 && my >= y && my < y+32) hovered = row;
        }
        if (entries.isEmpty()) text(g,"No scored chunks above the minimum yet.",x+16,71,0xffc4d3e3);
        if (hovered != null) {
            var f = hovered.features();
            text(g,"Built "+Math.round(f.exposed())+" | Tunnel "+f.tunnels()+" | Leaks "+f.leakedBlocks()+" (built "+f.leakedNonNatural()+") | Growth "+Math.round(100*f.growth())+"%",x+12,height-88,0xffc4d3e3);
        }
        int bw = (w-36)/3;
        button(g,"Export CSV",x+12,height-66,bw,22,accent);
        button(g,"Mark base",x+18+bw,height-66,bw,22,accent);
        button(g,"Not base",x+24+2*bw,height-66,bw,22,accent);
        button(g,"Previous",x+12,height-38,78,19,accent);
        button(g,"Next",x+96,height-38,55,19,accent);
        if (w >= 460) text(g,(page+1)+" / "+Math.max(1,(entries.size()+rows()-1)/rows()),x+165,height-33,0xffc4d3e3);
        var drill = ScrimmyClient.MODULES.find(DrillTrail.class);
        if (drill != null && drill.drillTarget() != null) {
            var target = drill.drillTarget();
            int tw = Math.min(208,w/2-16);
            button(g,"Clear target "+target.x()+", "+target.z(),x+w-tw-12,height-38,tw,19,accent);
        }
        super.render(g,mx,my,delta);
    }
    private void text(GuiGraphics g, String text, int x, int y, int color) {
        g.enableScissor(left()+9,y,left()+panelWidth()-9,y+12); ClientFonts.draw(g,text,x,y,color); g.disableScissor();
    }
    private void rowText(GuiGraphics g,String text,int x,int y,int maxWidth,int color) {
        g.enableScissor(x,y,x+Math.max(0,maxWidth),y+12); ClientFonts.draw(g,text,x,y,color); g.disableScissor();
    }
    private void button(GuiGraphics g, String text, int x, int y, int w, int h, int color) {
        RoundedRenderer.rounded(g,x,y,w,h,5,0x55557183); rowText(g,text,x+6,y+(h-9)/2,w-12,color);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (event.button() != 0) return super.mouseClicked(event,twice);
        double mx = event.x(), my = event.y(); int x = left(), w = panelWidth(), bw = (w-36)/3, tw = Math.min(208,w/2-16);
        for (int i = 0; i < rows(); i++) {
            int index = page*rows()+i, y = 62+i*36;
            if (index < entries.size() && inside(mx,my,x+w-143,y+3,125,26)) { module.sendToDrillTrail(entries.get(index)); return true; }
        }
        if (inside(mx,my,x+12,height-66,bw,22)) module.export();
        else if (inside(mx,my,x+18+bw,height-66,bw,22)) module.mark("base");
        else if (inside(mx,my,x+24+2*bw,height-66,bw,22)) module.mark("not base");
        else if (inside(mx,my,x+12,height-38,78,19)) page = Math.max(0,page-1);
        else if (inside(mx,my,x+96,height-38,55,19)) page = Math.min(Math.max(0,(entries.size()-1)/rows()),page+1);
        else if (inside(mx,my,x+w-tw-12,height-38,tw,19)) {
            var drill = ScrimmyClient.MODULES.find(DrillTrail.class); if (drill != null) drill.clearDrillTarget();
        }
        else return super.mouseClicked(event,twice);
        return true;
    }
    private boolean inside(double mx,double my,int x,int y,int w,int h) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { blur.close(); }
}
