package com.scrimmy.client.module;

import com.scrimmy.client.module.basefinding.*;
import com.scrimmy.client.setting.GroupSetting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkFinderV2Test {
    @Test void newModuleSitsBesideChunkFinderAndKeepsAdvancedControlsCollapsed() {
        ModuleManager manager = new ModuleManager(); var list = manager.in(Category.BASEFINDING);
        ChunkFinderV2 module = manager.find(ChunkFinderV2.class);
        assertEquals(list.indexOf(manager.find(Basefinding.class))+1,list.indexOf(module));
        assertEquals("ChunkFinder V2",module.name()); assertFalse(module.enabled());
        assertTrue(module.settings().stream().filter(s->s instanceof GroupSetting).allMatch(s->!((GroupSetting)s).get()));
        module.baseKey.set(77.0); module.notBaseKey.set(78.0);
        ChunkFinderV2 restored = new ChunkFinderV2(); restored.extraConfig(module.extraConfig());
        assertEquals(77,restored.baseKey.get()); assertEquals(78,restored.notBaseKey.get());
    }
    @Test void targetHookDoesNotToggleOrChangeExistingDrillSettings() {
        DrillTrail drill = new DrillTrail();
        assertNull(drill.drillTarget()); drill.setDrillTarget(-7,19);
        assertFalse(drill.enabled()); assertEquals(-7,drill.drillTarget().x()); assertEquals(19,drill.drillTarget().z());
        assertEquals("Smart",drill.mode.get()); assertEquals(96,drill.range.get());
        drill.clearDrillTarget(); assertNull(drill.drillTarget());
    }
}
