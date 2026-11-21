package com.scrimmy.client.notification;

import com.scrimmy.client.animation.Animation;
import com.scrimmy.client.animation.AnimationStyle;
import com.scrimmy.client.animation.Easing;

/** Bounded, retained presentation state. All times are monotonic seconds; no render-time allocations. */
public final class ToastQueue {
    public static final int LIMIT = 4;
    private final Toast[] visible = new Toast[LIMIT];
    private final Pending[] pending = new Pending[LIMIT];
    private int size, waiting;
    private NotificationSound sound = NotificationSound.SILENT;

    public ToastQueue() {
        for (int i = 0; i < LIMIT; i++) { visible[i] = new Toast(i); pending[i] = new Pending(); }
    }
    public int size() { return size; }
    public int waiting() { return waiting; }
    public Toast get(int index) { return visible[index]; }
    public void sound(NotificationSound sound) { this.sound = sound == null ? NotificationSound.SILENT : sound; }
    public void clear() {
        for (int i = 0; i < LIMIT; i++) { visible[i].identity = null; pending[i].identity = null; }
        size = waiting = 0;
    }

    public void show(Object identity, String name, boolean enabled, double now, double duration) {
        show(identity, name, enabled, now, duration, ToastStyle.CARD);
    }
    public void show(Object identity, String name, boolean enabled, double now, double duration, ToastStyle style) {
        for (int i = 0; i < size; i++) {
            Toast toast = visible[i];
            if (toast.identity == identity) {
                toast.name = name; toast.enabled = enabled; toast.closing = false;
                toast.deadline = now + duration + .4;
                toast.pulse.snap(1);
                return;
            }
        }
        for (int i = 0; i < waiting; i++) {
            Pending entry = pending[i];
            if (entry.identity == identity) { entry.name = name; entry.enabled = enabled; entry.duration = duration; entry.style = style; return; }
        }
        if (size < LIMIT && waiting == 0) { spawn(identity, name, enabled, now, duration, style); return; }
        if (waiting == LIMIT) removePending(); // Invisible overflow is bounded as well.
        Pending entry = pending[waiting++];
        entry.identity = identity; entry.name = name; entry.enabled = enabled; entry.duration = duration; entry.style = style;
        // Finish the oldest toast's exit before reusing its slot: never cut a visible toast off.
        if (size > 0) visible[size - 1].closing = true;
    }

    public void update(double now, double dt, boolean animations, double speed, AnimationStyle style) {
        double step = Math.max(0, dt) * speed;
        for (int i = size - 1; i >= 0; i--) {
            Toast toast = visible[i];
            toast.closing |= now >= toast.deadline;
            int targetOffset = 0;
            for (int j = 0; j < i; j++) targetOffset += visible[j].style.height() + 8;
            if (animations) {
                toast.reveal.tween(toast.closing ? 0 : 1, step, toast.closing ? .22 : style.duration(),
                        toast.closing ? Easing.OUT_CUBIC : Easing.OUT_BACK, 0);
                toast.offset.spring(targetOffset, step, style.frequency(), style.damping());
                toast.state.spring(toast.enabled ? 1 : 0, step, 24, 1);
                toast.pulse.tween(0, step, .3, Easing.OUT_QUINT, 0);
            } else {
                toast.reveal.snap(toast.closing ? 0 : 1);
                toast.offset.snap(targetOffset); toast.state.snap(toast.enabled ? 1 : 0); toast.pulse.snap(0);
            }
            if (toast.closing && toast.reveal.value() <= .001f) remove(i);
        }
        while (size < LIMIT && waiting > 0) {
            Pending entry = pending[0];
            spawn(entry.identity, entry.name, entry.enabled, now, entry.duration, entry.style);
            removePending();
        }
        if (waiting > 0 && size == LIMIT) visible[size - 1].closing = true;
    }

    private void spawn(Object identity, String name, boolean enabled, double now, double duration, ToastStyle style) {
        Toast toast = visible[size];
        System.arraycopy(visible, 0, visible, 1, size);
        visible[0] = toast; size++;
        toast.identity = identity; toast.name = name; toast.enabled = enabled; toast.style = style;
        toast.closing = false; toast.deadline = now + duration + .4; toast.generation++;
        toast.reveal.snap(0); toast.offset.snap(0); toast.state.snap(enabled ? 1 : 0); toast.pulse.snap(1);
        // A future sound provider must remain cosmetic, including when it fails.
        try { sound.onShown(enabled); } catch (RuntimeException ignored) {}
    }
    private void remove(int index) {
        Toast retired = visible[index]; retired.identity = null;
        int moved = size - index - 1;
        if (moved > 0) System.arraycopy(visible, index + 1, visible, index, moved);
        visible[--size] = retired;
    }
    private void removePending() {
        Pending retired = pending[0]; retired.identity = null;
        System.arraycopy(pending, 1, pending, 0, --waiting);
        pending[waiting] = retired;
    }

    public static final class Toast {
        public final int slot;
        public final Animation reveal = new Animation(), offset = new Animation(), state = new Animation(), pulse = new Animation();
        private Object identity;
        private String name;
        private ToastStyle style;
        private boolean enabled, closing;
        private double deadline;
        private long generation;
        Toast(int slot) { this.slot = slot; }
        public String name() { return name; }
        public ToastStyle style() { return style; }
        public boolean enabled() { return enabled; }
        public boolean closing() { return closing; }
        public long generation() { return generation; }
    }
    private static final class Pending {
        Object identity;
        String name;
        boolean enabled;
        double duration;
        ToastStyle style;
    }
}
