package com.scrimmy.client.module;

import com.scrimmy.client.module.combat.AutoTotem;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<>();
    private final List<Module> modulesView = Collections.unmodifiableList(modules);
    public ModuleManager() { register(new AutoTotem()); }
    private void register(Module module) { modules.add(module); }
    public List<Module> all() { return modulesView; }
    public void tick(Minecraft client) {
        for (Module module : modules) if (module.enabled()) module.onTick(client);
    }
}
