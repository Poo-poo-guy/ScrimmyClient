# Validation for the Chunk Finder and settings update

Use Java 25 and run `./gradlew clean build`, then `./gradlew runClient`.

## Automated coverage

- Growth stage weighting, exposed/blocked faces, bud orientation, connected and broken shells, cross-chunk identity, incomplete/unloaded boundaries and measured geode bounds.
- Positive/negative loaded-area evidence, overlap between distinct geodes, duplicate snapshot removal, normalization, smoothing, negative coordinates, bounded candidate ranges and empty evidence.
- Retained settings, one-line tooltips, fresh defaults, old aliases, malformed removed fields, empty BlockESP selections, particle style restoration and old geode log compatibility.
- Existing particle simulation, AutoTotem slot search, Freecam input/movement, ghost tracking/effects, BlockESP selection/recycling and GUI layout/transform regression tests.

## Dev checks performed

A temporary, unshipped client initializer created flat singleplayer worlds and placed three nearby groups of budding amethyst: two with grown clusters on their exposed faces and one with empty faces. The real palette-filtered scanner found three geodes, and the worker produced a 169-chunk estimate. World planes, geode bounds, glass labels and the primary Best estimate marker were drawn with Mesa software OpenGL.

- Fresh-config startup reached Minecraft's first-run UI and then a singleplayer world.
- An old version-1 config loaded with malformed removed settings, retained score/range values, the old ChunkFinder enable gate, seven BlockESP switches and an old custom Heart particle look. Startup assertions verified the migrated values.
- Moving 1,280 blocks away cleared the live geode evidence; returning caused rediscovery. Historical logs did not feed the live result.
- Minimum Score, Nametags and Highlight Chunks changed during the live run. The ClickGUI opened, the module disabled, the world disconnected and the client shut down.
- The smoke source and extra initializer were supplied from `/tmp` through a temporary Gradle init script. They are excluded from the final clean build and packaged mod.

Offline authentication/public-key requests were refused in the environment; integrated singleplayer and rendering still worked. This does not establish multiplayer behavior, physical audio output, hardware frame times, Retina sharpness or compatibility with third-party renderers.

## Remaining manual checks

- Test natural geodes on a real server, harvested clusters, incomplete chunk borders, rapid flying and view-distance changes. Compare loaded and unloaded rings of geodes around a known activity area.
- At Minecraft GUI scales 1–4 and client scales .75/1/1.5, resize the window and check tag text, overlap suppression, theme changes, blur disabled/enabled and distance fades. Check world highlights through solid terrain.
- Toggle every module, change its retained controls, save/restart, and verify values. Check all six particle styles against confirmed damage; AutoTotem offhand swaps; Freecam scroll/sprint; departure ghosts and spectator/tab warnings.
- Profile CPU, GPU and allocation behavior while flying on target hardware. Marker geometry and label state are reused; Minecraft still manages its internal render submissions.

The per-module removal/merge rationale is in [SETTINGS-CLEANUP.md](SETTINGS-CLEANUP.md). Scoring constants and implementation details are in [CHUNKFINDER.md](CHUNKFINDER.md).

## DrillTrail regression checks

- Automated tunnel fixtures cover X/Y/Z passages, cross-chunk continuity, horizontal and vertical bends, diagonal stairs, stair-lip spur removal, one-junction crossings, closed loops, short-pocket rejection, rooms/frontiers, water/placed-block tolerance, full lava plugs and deep drops. Trail memory, visits, caps, spline/bearing math and the exact six settings also have coverage.
- Fabric 1.21.11 + Mesa software OpenGL: the temporary harness detected carved tunnels/branches/stairs/a shaft and room, drew trail/glow/markers/HUD, walked a section, retained evidence after moving 1,280 blocks away, returned, toggled all display controls, and disabled/disconnected. Startup used the previous config with DrillTrail absent. A disconnect assertion verified session history clears while disabled.
- Chunk Finder ran alongside DrillTrail using the extracted shared world geometry. The harness asserted a positive read-only base score, then zero after Chunk Finder was disabled. DrillTrail continued independently.
- The temporary dev initializer and source live outside the repository and are removed from the final clean artifact. See [DRILLTRAIL.md](DRILLTRAIL.md) for limits and constant locations.

## SoundFinder checks

- The full suite has 143 tests after rebasing onto main. New coverage checks negative packet coordinates, repeated sounds at one block, positive and negative confirmation rules, rolling repeat scores, sustained activity, distinct-source chunk clustering, entity isolation, oldest-source eviction, burst limits, smooth expiry, angle selection, distance fading, and settings/ignore-list restoration.
- Before rebasing onto main's updated font/theme helpers, an unshipped client initializer under `/tmp` created a flat world with chest, door and dispenser blocks, then sent real server-to-client sound and level-event packets. The live module confirmed all three sources, ignored entity packets by default, merged repeated chest packets and raised their severity, accepted opt-in entity markers without confirmation, and wrote the optional local detection log.
- Mesa software OpenGL rendered source boxes, soft fills and glass HUD tags. A source behind a solid stone wall was selected and projected without block raycasting; a captured frame verified the glass pill and through-wall outline. Both depth modes were exercised.
- A second Fabric client built from the repository's pre-change HEAD connected to the modified client's published LAN world, loaded its player/world, and disconnected without a registry mismatch. The development LAN used offline test identities. This checks the pre-change client code, rather than an independently supplied historical jar.
- All SoundFinder implementation is in `src/client/java`. No common-source Java or synced registry registrations were added. HUD identifiers and render pipelines are client rendering resources, not synced game registry entries. The final clean build excludes the temporary initializers and test world.
- SoundFinder has no waypoint control because this client has no waypoint system. The existing detection log stores geode-specific records, so the optional sound log uses `config/scrimmy/soundfinder/detections.jsonl`; it records new sources and severity increases, and rotates at 4 MiB. Logging is off by default.
- Remaining manual checks: actual player chest/door interactions and physical audio on target hardware, GUI scales/theme variants, and compatibility with other rendering mods. The headless test machine had no OpenAL audio device.
