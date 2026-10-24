package com.scrimmy.client.setting;

import com.google.gson.JsonObject;
import com.scrimmy.client.config.SettingCodec;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BlockListSettingTest {
    @Test void registryValidationColorsRemovalAndConfigRoundTrip() {
        BlockListSetting setting = new BlockListSetting("blocks", "Blocks", id -> id.equals("minecraft:diamond_block"));
        AtomicInteger changes = new AtomicInteger(); setting.onChange(changes::incrementAndGet);
        assertTrue(setting.put(" diamond_block ", 0x4077dd99));
        assertEquals(1, changes.get()); assertFalse(setting.put("minecraft:missing", 0));
        assertFalse(setting.put("Bad ID", 0)); assertEquals(1, changes.get());
        JsonObject json = new JsonObject(); SettingCodec.write(json, setting);
        BlockListSetting restored = new BlockListSetting("blocks", "Blocks", id -> id.equals("minecraft:diamond_block"));
        json.getAsJsonObject("blocks").addProperty("minecraft:broken_color", "bad");
        json.getAsJsonObject("blocks").addProperty("minecraft:unknown", 123);
        SettingCodec.read(json, restored);
        assertEquals(setting.get(), restored.get());
        assertEquals(0x4077dd99, restored.get().get("minecraft:diamond_block"));
        assertThrows(UnsupportedOperationException.class, () -> restored.get().clear());
        restored.remove("minecraft:diamond_block"); assertTrue(restored.get().isEmpty());
        restored.reset(); assertEquals(Map.of(), restored.get());
    }
    @Test void largeAndCorruptListsAreBoundedAndCannotMutateBehindTheConfig() {
        BlockListSetting setting = new BlockListSetting("blocks", "Blocks", id -> true);
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put(null, 1); input.put("invalid", 2); input.put("minecraft:null", null);
        for (int i = 0; i < 1000; i++) input.put("minecraft:block_" + i, i);
        setting.set(input); input.clear();
        assertEquals(BlockListSetting.LIMIT, setting.get().size());
        assertFalse(setting.put("minecraft:one_more", 1));
        assertTrue(setting.put("minecraft:block_0", 0x11223344));
        assertEquals(0x11223344, setting.get().get("minecraft:block_0"));
    }
}
