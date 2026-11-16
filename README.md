# Scrimmy Client

Fabric client for Minecraft Java 1.21.11, with AutoTotem, HitParticles, and a glass ClickGUI.

## Build and run

Use a Java **25** JDK (check `java -version`), then run:

```sh
./gradlew build
./gradlew runClient
```

The mod is `build/libs/scrimmyclient-1.0.0.jar`. Install matching Fabric Loader and Fabric API when using it outside the development launcher. The first development launch downloads Minecraft's assets.

Press **Right Shift** in a world to open the GUI. The five categories are **Combat, Render, Client, Basefinding, Visual**. Default panels arrange in a row or wrap to available width and height, including the client's scale multiplier. Drag a header to give a panel a custom position; right click its header or click +/- to collapse it. Clicking any panel brings it forward. Left click a module row to toggle it; right click to expand its settings. Scroll the body or drag its scrollbar. Escape closes the GUI, or clears a module keybind while binding.

Open **ScrimmyClient+** in Client for live accent, blur, opacity, corner radius, scale, animation speed, and other appearance controls. Changes apply to every category. Reset restores the automatic panel layout.

## HitParticles

Enable HitParticles in Visual. Defaults produce about fourteen five-point stars per confirmed hit, with an accent gradient, soft additive halo, a gentle burst, and an eased pop/fade. The module is initially disabled, like AutoTotem.

The controls are Style, Amount, Trigger, Targets, Through walls, and Size. Styles are Starburst, Sparkle, Hearts, Galaxy, Confetti, and Minimal; each bundles shape, colors, motion, glow and other effects. Amount and size adjustments retain the selected style across restarts. Target lists support multiple selections and keep at least one choice.

Attack callbacks record hit position and vanilla critical-hit conditions; particles spawn only after a server damage packet attributes damage to the local player. Any damage dealt also accepts attributed projectile damage. Kill-only waits for a death event after attributed damage and cancels if another source deals subsequent damage. Attribution expires after two seconds. Servers must transmit ordinary damage/death packets; rejected swings, invulnerable targets, or entities that do not send those packets will not produce an effect. Critical detection uses the local vanilla attack conditions rather than a separate server critical flag.

## BlockESP

Open **Right Shift → Render → BlockESP**. Its six controls are Blocks, Extra blocks, Range, Style, Through walls, and Tracers. Blocks combines the category choices: Storage, Ores, Ancient Debris, Spawners, Portals, Beds, and Respawn Anchors. Storage, Ancient Debris, and Spawners start selected. Spawners includes trial spawners. The default look combines restrained category-colored outlines/fills with dimmer occluded highlights.

Expand **Extra blocks**, click **+ registry ID**, type an ID such as `minecraft:diamond_block`, and press Enter. The `minecraft:` prefix is optional. Select an entry to edit its color; click **x** to remove it. Unknown IDs and air are rejected. The list supports 256 entries.

Range is spherical, up to 256 blocks, within chunks already loaded by the game. Palette-filtered scans and nearest selection advance incrementally under a fixed 1 ms tick budget. Rendering displays up to 512 blocks and 32 tracers with reused CPU/GPU buffers. Outlines use shader antialiasing; fills use full block boxes. Through walls uses dimmer occluded highlights. Opacity, fade and thickness have fixed defaults.

World changes, disconnects, and disable release caches and render buffers. Chunk events handle packet reloads/unloads; one client-only mixin on the verified 1.21.11 `LevelChunk.setBlockState` handles server changes and local prediction. Fades and pulse use elapsed time; camera-relative vertices avoid precision jitter. Unit/regression tests cover bit traversal, nearest selection/cap/pool reuse, list validation, and config/color round trips. The full Gradle build passes, and the new shaders were compiled, linked, and drawn offscreen with Mesa OpenGL. Visual appearance, hardware FPS, multiplayer prediction behavior, and third-party renderer/shader compatibility still require in-game checks; no fixed FPS guarantee is possible across hardware and settings.

## Chunk Finder

In Basefinding, enable the module to mark nearby amethyst geodes and estimate the likely base area from their growth. Grown geodes add support within nine chunks; ungrown geodes narrow the area. The best chunk has a distinct highlight and Best estimate tag; secondary tags show Base chance and a score out of 100. These are relative scores, not calibrated probabilities.

Show Geodes, Highlight Chunks, Nametags, Minimum Score and Range are the only extra controls. Color and glass styling follow the client theme. See [CHUNKFINDER.md](CHUNKFINDER.md) for scoring/rendering details and [SETTINGS-CLEANUP.md](SETTINGS-CLEANUP.md) for the per-module audit and config migrations.

## DrillTrail

Enable **Basefinding → DrillTrail** to follow regular 3×3 tunnels with a smooth glowing center line. It detects horizontal/vertical passages, turns, stairs and intersections, dims visited parts, marks ends/rooms/junctions and warns about observed hazards. A glass HUD pill points to the nearest unexplored lead. Nearby high Chunk Finder scores brighten the trail when that module is enabled.

The six controls are Range, Dim Visited, Mark Leads, Show Direction, Hint HUD and Minimum Length. Trails survive chunk unloads within the current session and clear on world/server changes. Only loaded terrain can be scanned; irregular or heavily obstructed tunnels may be missed. See [DRILLTRAIL.md](DRILLTRAIL.md) for behavior, cache bounds, constants and validation.

## Configuration and extension

`config/scrimmy-client.json` saves theme, keybinds, enabled states, the retained typed settings and panel layout. Custom positions use viewport fractions; automatic panels reflow after a resize. Writes are atomic, debounced, and flushed on GUI close and client shutdown. Missing fields keep defaults; invalid individual setting values are ignored. Older panel configs retain their saved positions, and missing categories receive automatic defaults.

Add a `Module` subclass, give it a category and typed settings using `add(...)`, then add **one** `register(new YourModule())` line in `ModuleManager`. Ticks, settings persistence, and category routing are automatic. `group(...)` starts a collapsible subsection. AutoTotem still uses one inventory SWAP with button 40, respecting its container, health, and delay checks.

## Rendering and verification

GUI panels, controls, borders, tooltips, and scrollbars use the existing derivative-antialiased rounded-box SDF. Blur is captured once per frame, restored outside panels, and sampled through rounded masks. Mouse coordinates and text snapping share the same transform. Each panel owns a render stratum. Captures are reused until resize and released on screen close.

Particles use procedural antialiased SDF geometry, with no texture atlas. All particles/trail samples are pooled. Fixed 50 ms simulation plus interpolated position/rotation makes motion independent of rendering FPS. Distance/frustum/alpha culling and a fixed 512-particle cap bound work. Camera-relative vertices are built during Fabric's extraction phase; reused CPU buffers and rotating GPU buffers draw the whole effect in at most two passes (additive light and normal alpha). Depth testing is enabled unless Through walls is selected; particles do not write depth. Normal particles are sorted back to front. GPU/CPU buffers are released on disable, disconnect, world change, and shutdown; Minecraft owns registered shader pipelines and shared index buffers.

`./gradlew build` runs setting/config-migration, base-likelihood scoring, geode/tunnel analysis, trail memory, particle-math/pooling, AutoTotem slot-search, panel-layout, and GUI-transform regression tests. Hardware performance, multiplayer packet behavior, Retina sharpness, and visual compatibility with shader/renderer mods require in-game testing. See [TESTING.md](TESTING.md) for the checklist and changed-file map.
