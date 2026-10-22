package com.scrimmy.client.setting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GradientSetting extends Setting<List<GradientSetting.Stop>> {
    public record Stop(double position, int color) {}
    public GradientSetting(String key, String label, int... colors) {
        super(key, label, stops(colors));
    }
    private static List<Stop> stops(int[] colors) {
        List<Stop> result = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) result.add(new Stop(i / (double)Math.max(1, colors.length - 1), colors[i]));
        return List.copyOf(result);
    }
    @Override protected List<Stop> sanitize(List<Stop> value) {
        if (value == null || value.size() < 2 || value.size() > 8) return defaultValue();
        List<Stop> safe = value.stream().filter(s -> s != null && Double.isFinite(s.position()))
            .map(s -> new Stop(Math.max(0, Math.min(1, s.position())), s.color()))
            .sorted(Comparator.comparingDouble(Stop::position)).toList();
        return safe.size() < 2 ? defaultValue() : safe;
    }
    public void color(int index, int color) {
        List<Stop> next = new ArrayList<>(get());
        next.set(index, new Stop(next.get(index).position(), color));
        set(next);
    }
    public int position(int index, double position) {
        List<Stop> next = new ArrayList<>(get());
        Stop stop = new Stop(position, next.get(index).color());
        next.set(index, stop);
        set(next);
        return get().indexOf(stop);
    }
    public void insert() {
        if (get().size() >= 8) return;
        List<Stop> next = new ArrayList<>(get());
        double widest = -1, mid = .5;
        for (int i = 1; i < next.size(); i++) {
            double gap = next.get(i).position() - next.get(i - 1).position();
            if (gap > widest) { widest = gap; mid = (next.get(i).position() + next.get(i - 1).position()) / 2; }
        }
        next.add(new Stop(mid, sample(mid)));
        set(next);
    }
    public void remove(int index) {
        if (get().size() <= 2) return;
        List<Stop> next = new ArrayList<>(get()); next.remove(index); set(next);
    }
    public int sample(double fraction) {
        List<Stop> stops = get();
        if (fraction <= stops.getFirst().position()) return stops.getFirst().color();
        for (int i = 1; i < stops.size(); i++) {
            Stop a = stops.get(i - 1), b = stops.get(i);
            if (fraction <= b.position()) return mix(a.color(), b.color(), (fraction - a.position()) / Math.max(.00001, b.position() - a.position()));
        }
        return stops.getLast().color();
    }
    public static int mix(int a, int b, double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            result |= (int)(((a >>> shift) & 255) * (1 - f) + ((b >>> shift) & 255) * f) << shift;
        return result;
    }
}
