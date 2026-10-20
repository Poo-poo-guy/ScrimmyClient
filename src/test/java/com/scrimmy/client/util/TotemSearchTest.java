package com.scrimmy.client.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotemSearchTest {
    @Test void respectsHotbarPriorityAndInventoryBoundaries() {
        assertEquals(40, TotemSearch.find(true, slot -> slot == 15 || slot == 40));
        assertEquals(15, TotemSearch.find(false, slot -> slot == 15 || slot == 40));
        assertEquals(44, TotemSearch.find(true, slot -> slot == 44));
        assertEquals(9, TotemSearch.find(false, slot -> slot == 9));
    }

    @Test void neverSelectsArmorOrOffhandSlots() {
        assertEquals(-1, TotemSearch.find(true, slot -> slot < 9 || slot >= 45));
        assertEquals(-1, TotemSearch.find(false, slot -> false));
    }
}
