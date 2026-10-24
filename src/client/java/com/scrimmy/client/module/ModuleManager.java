package com.scrimmy.client.module;

import com.scrimmy.client.module.combat.AutoTotem;
import com.scrimmy.client.module.visual.HitParticles;
import com.scrimmy.client.module.visual.BlockESPModule;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<>();
    private final List<Module> modulesView = Collections.unmodifiableList(modules);
    private final Map<Category, List<Module>> categories = new EnumMap<>(Category.class);
    private final Map<Category, List<Module>> categoryViews = new EnumMap<>(Category.class);
    public ModuleManager() {
        for (Category category : Category.values()) {
            List<Module> entries = new ArrayList<>();
            categories.put(category, entries);
            categoryViews.put(category, Collections.unmodifiableList(entries));
        }
        register(new AutoTotem());
        register(new HitParticles());
        register(new BlockESPModule());
    }
    private void register(Module module) {
        modules.add(module);
        categories.get(module.category()).add(module);
    }
    public <T extends Module> T find(Class<T> type) {
        for (Module module : modules) if (type.isInstance(module)) return type.cast(module);
        return null;
    }
    public List<Module> all() { return modulesView; }
    public List<Module> in(Category category) { return categoryViews.get(category); }
    public void tick(Minecraft client) {
        for (Module module : modules) if (module.enabled()) module.onTick(client);
    }
}
