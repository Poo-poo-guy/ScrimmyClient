package com.scrimmy.client.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Register one entry plus its resource providers/metrics to add a family to every client surface. */
public final class FontCatalog {
    public record Family(String name, String resource, String regular, String semibold) {}
    private static final List<Family> FAMILIES = new ArrayList<>();
    static {
        FAMILIES.add(new Family("Xuong", "xuong", "/assets/scrimmyclient/fonts/Xuong.ttf", null));
        register("Inter", "inter"); register("Poppins", "poppins"); register("Montserrat", "montserrat");
    }
    private FontCatalog() {}
    public static void register(String name, String resource) { FAMILIES.add(new Family(name, resource, "/assets/scrimmyclient/font/" + resource + "/regular.ttf", "/assets/scrimmyclient/font/" + resource + "/semibold.ttf")); }
    public static List<Family> entries() { return Collections.unmodifiableList(FAMILIES); }
    public static String[] names() { return FAMILIES.stream().map(Family::name).toArray(String[]::new); }
}
