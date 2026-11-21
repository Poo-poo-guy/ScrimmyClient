# Scrimmy Client registry audit and LAN fix

Repository: `/workspace/ScrimmyClient`. Audited production Java sources, entrypoints,
build/source sets, and resources, including direct and static-import registration
APIs, builders, particle factories, attachments, and data components.

## Complete list of Minecraft registry additions before the fix

These were the only additions. Line numbers refer to the pre-fix source at
`e90b3cf` (the build containing the notification sound registration).

| Registry | Entry | Full file path and original line |
| --- | --- | --- |
| `minecraft:sound_event` | `scrimmyclient:notification_on` | `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/notification/ClientNotificationSounds.java:20` |
| `minecraft:sound_event` | `scrimmyclient:notification_off` | `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/notification/ClientNotificationSounds.java:21` |

Both were inserted by `Registry.register(BuiltInRegistries.SOUND_EVENT, ...)`,
called through `NotificationAudio.initialize` from the client entrypoint. A client
initializer is still inside the same JVM as an integrated LAN server: mutating a
built-in registry there makes those entries visible to registry synchronization.
They match the reported count of two unknown entries on an unmodded/older peer.

No `Registry.registerForHolder` calls were found. No other Minecraft registry
additions, custom registries, or registered blocks, items, entity types,
ParticleTypes, attachment types, data components, or game rules were found.

## Remaining Minecraft registry additions after the fix

```text
[]
```

`ClientNotificationSounds` now creates the same two `SoundEvent` handles directly
with `SoundEvent.createVariableRangeEvent`, without inserting them into a registry.
`NotificationAudio` still passes those handles to `SimpleSoundInstance.forUI` and
the client SoundManager. SoundManager resolves the unchanged resource definitions
by identifier; no synchronized raw sound id is required.

Both `assets/scrimmyclient/sounds.json` and `sounds/aula_click1.ogg` are byte-for-byte
unchanged. Current volume, pitch, variation, tick limit, NotificationSound hook,
source filtering, module logic, GUI, and notification rendering are unchanged.

## Other registration/read sites inspected (not synced entries)

The following graphics registrations remain intentionally. They are local render
pipeline/cache setup and are not `net.minecraft.core.Registry` entries.

| Full file path | Client-local pipeline identifiers (`scrimmyclient:` prefix) |
| --- | --- |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/SdfFonts.java` | `pipeline/sdf_text` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/WorldOverlayRenderer.java` | `pipeline/world_overlay` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/RoundedRenderer.java` | `pipeline/rounded`, `pipeline/glass` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/LogoutGhostRenderer.java` | `pipeline/logout_ghosts` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/HitParticleRenderer.java` | `pipeline/hit_particles_0`, `pipeline/hit_particles_1`, `pipeline/hit_particles_2`, `pipeline/hit_particles_3` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/BlockEspRenderer.java` | `pipeline/block_esp_0`, `pipeline/block_esp_1`, `pipeline/block_esp_2` |
| `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/AntiVanishHeadRenderer.java` | `pipeline/anti_vanish_head`, `pipeline/anti_vanish_hat`, `pipeline/logout_ghost_head` |

Other similarly named APIs inspected:

- `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/AntiVanishEffect.java`:
  `SpecialGuiElementRegistry.register` installs a local renderer for
  `AntiVanishHeadRenderer.HeadState`; it does not add networked content.
- `/workspace/ScrimmyClient/src/main/java/com/scrimmy/client/animation/AnimationStyle.java`:
  local animation catalog (Smooth, Spring, Cascade, Zoom).
- `/workspace/ScrimmyClient/src/main/java/com/scrimmy/client/theme/SurfaceStyle.java`:
  local surface catalog (Glass, Solid).
- `/workspace/ScrimmyClient/src/main/java/com/scrimmy/client/theme/FontCatalog.java`
  and `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/render/ClientFonts.java`:
  local font catalogs (Xuong, Inter, Poppins, Montserrat).
- `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/module/ModuleManager.java`:
  local module lists; Fabric lifecycle/render/resource callbacks elsewhere only
  register local listeners.
- `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/basefinding/DrillTrailScanner.java`,
  `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/basefinding/ChunkFinderScanner.java`,
  and `/workspace/ScrimmyClient/src/client/java/com/scrimmy/client/module/visual/BlockESPModule.java`:
  iterate/query existing vanilla block registry entries; they never add entries.

HitParticles already uses its own pooled numeric particle state and custom vertex
batches in `HitParticleRenderer`; it has no `ParticleType`, particle factory, or
particle registry entry. No particle implementation or module behavior was changed.

## Client source-set boundary and entrypoints

All notification classes and the particle pool/math helpers are now in
`src/client/java/com/scrimmy/client/{notification,particle}`. Their Theme owner also
moved to `src/client/java/com/scrimmy/client/theme/Theme.java` so the common Java
source set does not reference client feature classes. These moves preserve every
package, class name, method, setting key, and implementation. No sound, particle,
or Minecraft registry code remains in `src/main/java`.

`/workspace/ScrimmyClient/src/main/resources/fabric.mod.json` is unchanged:
`environment` is `client`, the only entrypoint is
`client: com.scrimmy.client.ScrimmyClient`, and there is no common/server initializer.
The requested sound assets remain at their original `src/main/resources` paths.

## Validation

- Clean Gradle build, remapping, and all 135 tests passed.
- A live fixed client enumerated every built-in registry: the complete list of
  `scrimmyclient` entries was `[]`.
- The fixed client created and published a flat LAN world.
- A second instance using the pre-fix JAR joined and remained in play. Its test
  verified the old build still had the two registered SoundEvents.
- A second instance with no Scrimmy JAR joined and remained in play. Its test
  verified the Scrimmy mod was absent and it had zero Scrimmy sound registry entries.
- The fixed host started a notification audio channel before and after both LAN
  connections, and verified that playback did not insert the event into the registry.
- Sound JSON/OGG hashes, module logic, notification layouts, and GUI are unchanged.

The LAN test used isolated offline development profiles and a test-only server
authentication override. Authenticated account 1/account 2 login remains a manual
check; production authentication/networking code was not modified. All test worlds,
launchers, and helper mods are outside the repository/distributed JAR.
