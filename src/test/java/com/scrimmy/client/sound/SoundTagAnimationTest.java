package com.scrimmy.client.sound;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.scrimmy.client.sound.SoundTagAnimation.Style.*;

class SoundTagAnimationTest {
    private void step(SoundTagAnimation animation, boolean show, boolean linger, double dt) {
        animation.step(show, linger, dt, .2, .25, .15);
    }
    private void interval(SoundTagAnimation a, boolean show, boolean linger, double seconds, int fps) {
        while (seconds > 1e-10) {
            double dt = Math.min(seconds, 1.0 / fps); step(a, show, linger, dt); seconds -= dt;
        }
    }
    @Test void entranceOvershootsAndExitEasesDownUntilExactlyZero() {
        SoundTagAnimation a = new SoundTagAnimation();
        step(a,true,false,0);
        assertEquals(0,a.progress()); assertEquals(0,a.alpha());
        assertEquals(.85,a.scaleX(POP)); assertEquals(4,a.slide(POP));
        step(a,true,false,.15); assertTrue(a.scaleX(POP)>1); assertTrue(a.alpha()>.95);
        step(a,true,false,.05); assertEquals(1,a.progress()); assertEquals(1,a.scaleX(POP)); assertEquals(0,a.slide(POP));
        step(a,false,false,.125);
        assertEquals(.5,a.progress(),1e-9); assertTrue(a.alpha()>.5); assertTrue(a.slide(POP)<1.5);
        assertTrue(a.scaleX(POP)<1 && a.scaleX(POP)>.92);
        step(a,false,false,.124); assertTrue(a.progress()>0); assertTrue(a.alpha()>0);
        step(a,false,false,.001); assertEquals(0,a.progress()); assertEquals(0,a.alpha());
        assertEquals(.92,a.scaleX(POP)); assertEquals(3,a.slide(POP));
    }
    @Test void lingerConsumesOnlyItsShareOfALongFrame() {
        SoundTagAnimation a=new SoundTagAnimation(); step(a,true,false,.2);
        step(a,false,true,.149); assertFalse(a.hiding()); assertEquals(1,a.progress());
        step(a,false,true,.126); assertTrue(a.hiding()); assertEquals(.5,a.progress(),1e-9);
        step(a,false,true,.125); assertEquals(0,a.progress());
        SoundTagAnimation hitch=new SoundTagAnimation(); step(hitch,true,false,.2);
        step(hitch,false,true,.4); assertEquals(0,hitch.progress());
    }
    @Test void reentryDuringLingerCancelsTheOldDeadline() {
        SoundTagAnimation a=new SoundTagAnimation(); step(a,true,false,.2);
        step(a,false,true,.14); step(a,true,false,.02); assertFalse(a.hiding());
        step(a,false,true,.14); assertEquals(1,a.progress()); assertFalse(a.hiding());
        step(a,false,true,.02); assertTrue(a.hiding()); assertEquals(.96,a.progress(),1e-9);
    }
    @Test void reversingAPartialAnimationKeepsItsCurrentShapeAndLineOpacity() {
        SoundTagAnimation a=new SoundTagAnimation(); step(a,true,false,.08);
        double progress=a.progress(),alpha=a.alpha(),scale=a.scaleX(POP),y=a.slide(POP),line=a.lineAlpha(1);
        step(a,false,false,0);
        assertEquals(progress,a.progress());assertEquals(alpha,a.alpha());assertEquals(scale,a.scaleX(POP));
        assertEquals(y,a.slide(POP));assertEquals(line,a.lineAlpha(1));
        step(a,false,false,.04); progress=a.progress();alpha=a.alpha();scale=a.scaleX(POP);y=a.slide(POP);line=a.lineAlpha(1);
        step(a,true,false,0);
        assertEquals(progress,a.progress());assertEquals(alpha,a.alpha());assertEquals(scale,a.scaleX(POP));
        assertEquals(y,a.slide(POP));assertEquals(line,a.lineAlpha(1));
        step(a,true,false,.2); assertEquals(1,a.progress()); assertEquals(1,a.alpha());
    }
    @Test void changingFpsDoesNotChangeTheTimelineOrPulse() {
        SoundTagAnimation reference=null;
        for(int fps:new int[]{24,30,60,144,240}) {
            SoundTagAnimation a=new SoundTagAnimation();
            interval(a,true,false,.12,fps); interval(a,false,true,.24,fps);
            interval(a,true,false,.18,fps); interval(a,false,false,.07,fps);
            if(reference!=null) {
                assertEquals(reference.progress(),a.progress(),1e-8);assertEquals(reference.alpha(),a.alpha(),1e-8);
                assertEquals(reference.scaleX(POP),a.scaleX(POP),1e-8);assertEquals(reference.slide(POP),a.slide(POP),1e-8);
                assertEquals(reference.pulse(),a.pulse(),1e-8);
                for(int line=0;line<SoundTagAnimation.LINES;line++)assertEquals(reference.lineAlpha(line),a.lineAlpha(line),1e-8);
            }
            reference=a;
        }
    }
    @Test void linesStaggerByThirtyMillisecondsAndFinishAfterThePill() {
        SoundTagAnimation a=new SoundTagAnimation(); step(a,true,false,.029);
        assertTrue(a.lineAlpha(0)>0);assertEquals(0,a.lineAlpha(1));
        step(a,true,false,.002); assertTrue(a.lineAlpha(1)>0);assertEquals(0,a.lineAlpha(2));
        step(a,true,false,.030); assertTrue(a.lineAlpha(2)>0);assertEquals(0,a.lineAlpha(3));
        step(a,true,false,.060); assertTrue(a.lineAlpha(4)>0);
        step(a,true,false,.079); assertEquals(1,a.progress());assertTrue(a.lineAlpha(4)<1);
        step(a,true,false,.12);for(int line=0;line<5;line++)assertEquals(1,a.lineAlpha(line),1e-9);
    }
    @Test void stylesZeroDurationsAndSlowPulseHaveTheirExpectedEndpoints() {
        SoundTagAnimation a=new SoundTagAnimation(); step(a,true,false,0);
        assertEquals(1,a.scaleX(FADE)); assertEquals(0,a.slide(FADE));
        assertEquals(1,a.scaleY(SLIDE));assertEquals(4,a.slide(SLIDE));
        assertEquals(.85,a.scaleX(GLASS_EXPAND));assertEquals(.925,a.scaleY(GLASS_EXPAND),1e-9);
        a.step(true,false,.7,0,0,0);assertEquals(1,a.progress());assertEquals(1.15,a.pulse(),1e-9);
        a.step(true,false,1.4,0,0,0);assertEquals(.85,a.pulse(),1e-9);
        a.step(false,false,0,0,0,0);assertEquals(0,a.progress());assertEquals(0,a.alpha());
        for(int line=0;line<5;line++)assertEquals(0,a.lineAlpha(line));
        step(a,true,false,0);assertEquals(.85,a.scaleX(POP));assertEquals(4,a.slide(POP));
        assertEquals(POP,SoundTagAnimation.style("Pop"));assertEquals(FADE,SoundTagAnimation.style("Fade"));
        assertEquals(SLIDE,SoundTagAnimation.style("Slide"));assertEquals(GLASS_EXPAND,SoundTagAnimation.style("Glass expand"));
    }
    @Test void steppingAndConeSelectionDoNotAllocateAfterInitialization() {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        SoundTagAnimation animation=new SoundTagAnimation();SoundTagSelection<Object> selection=new SoundTagSelection<>();Object source=new Object();
        long thread=Thread.currentThread().threadId();
        for(int pass=0;pass<2;pass++) {
            long before=bean.getThreadAllocatedBytes(thread);
            for(int frame=0;frame<100_000;frame++) {
                selection.begin(4,2);selection.consider(source,20,frame%120<60?1:0);selection.finish();
                animation.step(selection.wanted(source),selection.mayLinger(source),1.0/144,.2,.25,.15);
                if(animation.hiding())selection.release(source);
            }
            long allocated=bean.getThreadAllocatedBytes(thread)-before;
            if(pass==1)assertEquals(0,allocated,"Animation/selection hot paths must not create per-frame objects");
        }
    }
}
