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
