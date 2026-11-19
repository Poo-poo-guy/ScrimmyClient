package com.scrimmy.client.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Register one entry plus its resource providers/metrics to add a family to every client surface. */
public final class FontCatalog {
    public record Family(String name, String resource) {}
    private static final List<Family> FAMILIES = new ArrayList<>();
    static {
        register("Inter", "inter"); register("Poppins", "poppins");
        register("Montserrat", "montserrat"); register("Roboto", "roboto");
    }
    private FontCatalog() {}
    public static void register(String name, String resource) { FAMILIES.add(new Family(name, resource)); }
    public static List<Family> entries() { return Collections.unmodifiableList(FAMILIES); }
    public static String[] names() { return FAMILIES.stream().map(Family::name).toArray(String[]::new); }
}
