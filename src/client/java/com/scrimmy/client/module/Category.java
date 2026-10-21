package com.scrimmy.client.module;

public enum Category {
    COMBAT("Combat"), RENDER("Render"), CLIENT("Client"), BASEFINDING("Basefinding");

    private final String title;
    Category(String title) { this.title = title; }
    public String title() { return title; }
}
