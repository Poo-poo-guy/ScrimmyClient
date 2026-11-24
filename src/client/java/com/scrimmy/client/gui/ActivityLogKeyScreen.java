package com.scrimmy.client.gui;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.module.basefinding.ActivityLog;
import com.scrimmy.client.render.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Uses the normal press-a-key / Escape-to-clear binding interaction. */
public final class ActivityLogKeyScreen extends Screen {
    private final ActivityLog module;
    private final Screen parent;
    private final PanelBlur blur=new PanelBlur();
    public ActivityLogKeyScreen(ActivityLog module,Screen parent){super(Component.literal("ActivityLog panel key"));this.module=module;this.parent=parent;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(KeyEvent event){
        module.panelKey.set(event.key()==GLFW.GLFW_KEY_ESCAPE?-1.0:(double)event.key());onClose();return true;
    }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float delta){if(ScrimmyClient.THEME.blur.get()>=1)blur.capture(minecraft);else blur.close();}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        int w=Math.min(380,width-16),x=(width-w)/2,y=height/2-35;
        if(blur.texture()!=null)RoundedRenderer.glass(g,x,y,w,70,10,0xffffffff,blur.texture());
        RoundedRenderer.panel(g,x,y,w,70,10,ThemeSurfaces.fill(),ScrimmyClient.THEME.accent.get(),.2f,.5f);
        ClientFonts.draw(g,"ActivityLog panel key",x+12,y+14,ScrimmyClient.THEME.accent.get());
        ClientFonts.draw(g,"Press a key · Esc to clear",x+12,y+38,0xffc4d3e3);
        super.render(g,mx,my,delta);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void removed(){blur.close();}
}
