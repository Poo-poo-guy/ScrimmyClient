package com.scrimmy.client.sound;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoundTagSelectionTest {
    private void consider(SoundTagSelection<Object> selection,Object source,double angle,double distance) {
        selection.consider(source,distance,Math.cos(Math.toRadians(angle)));
    }
    @Test void outerConeHoldsTheActiveTargetButCannotAcquireANewOne() {
        Object a=new Object(); SoundTagSelection<Object> selection=new SoundTagSelection<>();
        selection.begin(4,2);consider(selection,a,5,10);selection.finish();assertNull(selection.active());
        selection.begin(4,2);consider(selection,a,3.99,10);selection.finish();assertSame(a,selection.active());assertTrue(selection.wanted(a));
        selection.begin(4,2);consider(selection,a,5.99,10);selection.finish();assertTrue(selection.wanted(a));
        selection.begin(4,2);consider(selection,a,6.01,10);selection.finish();assertFalse(selection.wanted(a));assertTrue(selection.mayLinger(a));
        SoundTagAnimation animation=new SoundTagAnimation();animation.step(true,false,.2,.2,.25,.15);
        animation.step(selection.wanted(a),selection.mayLinger(a),.14,.2,.25,.15);assertFalse(animation.hiding());
        animation.step(selection.wanted(a),selection.mayLinger(a),.02,.2,.25,.15);assertTrue(animation.hiding());
        selection.release(a);
        selection.begin(4,2);consider(selection,a,5.99,10);selection.finish();assertNull(selection.active());
    }
    @Test void switchingTargetsCrossfadesTwoIndependentStatesWithoutLingerOrSnapping() {
        Object a=new Object(),b=new Object();SoundTagSelection<Object> selection=new SoundTagSelection<>();
        SoundTagAnimation old=new SoundTagAnimation(),next=new SoundTagAnimation();
        selection.begin(4,2);consider(selection,a,0,10);selection.finish();old.step(selection.wanted(a),false,.2,.2,.25,.15);
        selection.begin(4,2);consider(selection,a,5,10);consider(selection,b,0,8);selection.finish();
        assertSame(b,selection.active());assertFalse(selection.mayLinger(a));
        old.step(selection.wanted(a),selection.mayLinger(a),.1,.2,.25,.15);
        next.step(selection.wanted(b),selection.mayLinger(b),.1,.2,.25,.15);
        assertTrue(old.hiding());assertFalse(next.hiding());
        assertEquals(.6,old.progress(),1e-9);assertEquals(.5,next.progress(),1e-9);
        assertTrue(old.alpha()>0);assertTrue(next.alpha()>0);
        old.step(false,false,.15,.2,.25,.15);assertEquals(0,old.progress());assertEquals(.5,next.progress());
    }
    @Test void closestInnerSourceWinsAndUnloadedOrDisabledSourcesCannotLinger() {
        Object a=new Object(),b=new Object();SoundTagSelection<Object> selection=new SoundTagSelection<>();
        selection.begin(4,2);consider(selection,a,0,20);consider(selection,b,3,8);selection.finish();assertSame(b,selection.active());
        selection.begin(4,2);consider(selection,a,0,20);selection.finish();assertSame(a,selection.active());assertFalse(selection.mayLinger(b));
        selection.begin(4,2);selection.finish();assertNull(selection.active());assertFalse(selection.mayLinger(a));
        selection.clear();assertNull(selection.active());
    }
}
