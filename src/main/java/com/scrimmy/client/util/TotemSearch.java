package com.scrimmy.client.util;

import java.util.function.IntPredicate;

public final class TotemSearch {
    private TotemSearch() {}

    /** Inventory menu slots: 9-35 main inventory; 36-44 hotbar. */
    public static int find(boolean hotbarFirst, IntPredicate hasTotem) {
        int first = hotbarFirst ? scan(36, 45, hasTotem) : scan(9, 36, hasTotem);
        if (first >= 0) return first;
        return hotbarFirst ? scan(9, 36, hasTotem) : scan(36, 45, hasTotem);
    }

    private static int scan(int start, int end, IntPredicate hasTotem) {
        for (int slot = start; slot < end; slot++) if (hasTotem.test(slot)) return slot;
        return -1;
    }
}
