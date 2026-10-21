package com.scrimmy.client.config;

import com.google.gson.*;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.module.ModuleManager;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.theme.Theme;
import com.scrimmy.client.theme.PanelState;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.EnumMap;
import java.util.Map;

public final class ConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger("ScrimmyClient");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("scrimmy-client.json");
    private final Theme theme;
    private final ModuleManager modules;
    private final Map<Category, PanelState> panels = new EnumMap<>(Category.class);
    private boolean loading;
    private boolean dirty;
    private long lastChange;
    private int guiKey = 344; // GLFW right shift

    public ConfigStore(Theme theme, ModuleManager modules) {
        this.theme = theme; this.modules = modules;
        resetPanels();
        theme.onChange(this::changed);
        modules.all().forEach(m -> m.onChange(this::changed));
    }
    public int guiKey() { return guiKey; }
    public void guiKey(int key) { if (guiKey != key) { guiKey = key; changed(); } }
    public PanelState panel(Category category) { return panels.get(category); }
    public void panel(Category category, PanelState state) {
        if (!state.equals(panels.put(category, state))) changed();
    }
    private void resetPanels() {
        for (Category category : Category.values())
            panels.put(category, new PanelState(category.ordinal() / 3.0, 0.08, true));
    }

    private void changed() {
        if (loading) return;
        dirty = true;
        lastChange = System.nanoTime();
    }

    public void tick() {
        if (dirty && System.nanoTime() - lastChange >= 250_000_000L) save();
    }

    public void load() {
        if (!Files.isRegularFile(path)) return;
        loading = true;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject profiles = object(root, "profiles");
            JsonObject profile = object(profiles, "default");
            JsonObject themeJson = object(profile, "theme");
            for (Setting<?> setting : theme.settings()) readSetting(themeJson, setting);
            JsonObject moduleJson = object(profile, "modules");
            for (Module module : modules.all()) {
                JsonObject entry = object(moduleJson, module.name());
                if (entry.has("enabled")) module.enabled(entry.get("enabled").getAsBoolean());
                if (entry.has("keybind")) module.keybind(entry.get("keybind").getAsInt());
                JsonObject values = object(entry, "settings");
                for (Setting<?> setting : module.settings()) readSetting(values, setting);
            }
            if (profile.has("guiKey")) guiKey = profile.get("guiKey").getAsInt();
            JsonObject layout = object(profile, "panels");
            for (Category category : Category.values()) {
                JsonObject entry = object(layout, category.name());
                PanelState fallback = panels.get(category);
                try {
                    panels.put(category, new PanelState(
                        entry.has("x") ? entry.get("x").getAsDouble() : fallback.x(),
                        entry.has("y") ? entry.get("y").getAsDouble() : fallback.y(),
                        entry.has("open") ? entry.get("open").getAsBoolean() : fallback.open()));
                } catch (RuntimeException ignored) { /* Preserve this panel's default. */ }
            }
        } catch (Exception e) {
            theme.reset();
            resetPanels();
            guiKey = 344;
            for (Module module : modules.all()) {
                module.enabled(false);
                module.keybind(-1);
                module.settings().forEach(Setting::reset);
            }
            LOG.warn("Could not load Scrimmy Client config; using defaults", e);
        } finally { loading = false; }
    }

    public void reset() {
        loading = true;
        theme.reset();
        resetPanels();
        guiKey = 344;
        for (Module module : modules.all()) {
            module.enabled(false);
            module.keybind(-1);
            module.settings().forEach(Setting::reset);
        }
        loading = false;
        save();
    }

    public void save() {
        if (loading) return;
        dirty = false;
        JsonObject profile = new JsonObject();
        profile.addProperty("guiKey", guiKey);
        JsonObject layout = new JsonObject();
        for (Category category : Category.values()) {
            PanelState state = panels.get(category);
            JsonObject entry = new JsonObject();
            entry.addProperty("x", state.x()); entry.addProperty("y", state.y()); entry.addProperty("open", state.open());
            layout.add(category.name(), entry);
        }
        profile.add("panels", layout);
        JsonObject themeJson = new JsonObject();
        for (Setting<?> setting : theme.settings()) writeSetting(themeJson, setting);
        profile.add("theme", themeJson);
        JsonObject moduleJson = new JsonObject();
        for (Module module : modules.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", module.enabled());
            entry.addProperty("keybind", module.keybind());
            JsonObject values = new JsonObject();
            for (Setting<?> setting : module.settings()) writeSetting(values, setting);
            entry.add("settings", values);
            moduleJson.add(module.name(), entry);
        }
        profile.add("modules", moduleJson);
        JsonObject profiles = new JsonObject(); profiles.add("default", profile);
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("activeProfile", "default"); root.add("profiles", profiles);
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "scrimmy-", ".tmp");
            try {
                Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) {
            dirty = true;
            lastChange = System.nanoTime();
            LOG.error("Could not save Scrimmy Client config", e);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }
    private static void writeSetting(JsonObject object, Setting<?> setting) {
        Object value = setting.get();
        if (value instanceof Number n) object.addProperty(setting.key(), n);
        else if (value instanceof Boolean b) object.addProperty(setting.key(), b);
        else object.addProperty(setting.key(), String.valueOf(value));
    }
    private static void readSetting(JsonObject object, Setting<?> setting) {
        if (!object.has(setting.key())) return;
        try {
            JsonElement value = object.get(setting.key());
            if (setting instanceof BooleanSetting s) s.set(value.getAsBoolean());
            else if (setting instanceof NumberSetting s) s.set(value.getAsDouble());
            else if (setting instanceof ModeSetting s) s.set(value.getAsString());
            else if (setting instanceof ColorSetting s) s.set(value.getAsInt());
        } catch (RuntimeException ignored) { /* One bad field does not discard the rest. */ }
    }
}
