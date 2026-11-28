package com.scrimmy.client.notification;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class NotificationSoundAssetsTest {
    @Test void bothPreloadedEventsResolveToTheSameBundledVorbisRecording() throws Exception {
        try (var definition = getClass().getResourceAsStream("/assets/scrimmyclient/sounds.json")) {
            assertNotNull(definition, "Sound definitions must be packaged");
            var events = JsonParser.parseReader(new InputStreamReader(definition, StandardCharsets.UTF_8)).getAsJsonObject();
            var recordings = new HashSet<String>();
            for (String event : new String[]{"notification_on", "notification_off"}) {
                var files = events.getAsJsonObject(event).getAsJsonArray("sounds");
                assertEquals(1, files.size());
                var sound = files.get(0).getAsJsonObject();
                assertTrue(sound.get("preload").getAsBoolean(), "UI audio should use the preloaded cache");
                String name = sound.get("name").getAsString(); recordings.add(name);
                String[] id = name.split(":", 2);
                try (var audio = getClass().getResourceAsStream("/assets/" + id[0] + "/sounds/" + id[1] + ".ogg")) {
                    assertNotNull(audio, "Missing recording: " + name);
                    byte[] bytes = audio.readNBytes(128);
                    assertTrue(bytes.length > 32);
                    assertEquals("OggS", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
                    assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("vorbis"), "Minecraft UI audio must be Vorbis");
                }
            }
            assertEquals(java.util.Set.of("scrimmyclient:aula_click1"), recordings);
        }
    }
}
