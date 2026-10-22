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

Settings are organized into collapsible General, Shape, Color, Motion, Effects, Sound, and Presets sections. Click a mode's header to open its dropdown. Shape pools and target lists support multiple selections and always retain at least one selection. Color pickers expose hue, saturation, brightness, and alpha. Gradient and palette editors support two to eight stops: select a swatch, expand the editor, change its position/color, and use +/- to add/remove stops.

Available shapes: stars with 4/5/6/8 points, curved sparkle, soft circle, heart, diamond, plus, snowflake, triangle, ring, or a random mix. Motion: radial burst, fountain, spiral, explosion, or orbit. Effects include outlines, glow, normal/additive blending, twinkle, trails, five easing curves, and optional sound. Built-in looks are Starburst, Sparkle, Hearts, Galaxy, Confetti, and Minimal. Editing a look switches it to Custom. Select one of eight user slots and use Save/Load; hover those buttons for status. Settings apply immediately; spawn properties such as lifetime, initial velocity, and random shape are sampled for the next burst.

Attack callbacks record hit position and vanilla critical-hit conditions; particles spawn only after a server damage packet attributes damage to the local player. Any damage dealt also accepts attributed projectile damage. Kill-only waits for a death event after attributed damage and cancels if another source deals subsequent damage. Attribution expires after two seconds. Servers must transmit ordinary damage/death packets; rejected swings, invulnerable targets, or entities that do not send those packets will not produce an effect. Critical detection uses the local vanilla attack conditions rather than a separate server critical flag.

## Configuration and extension

`config/scrimmy-client.json` saves theme, keybinds, enabled states, all typed settings, subsection states, user particle presets, and panel layout. Custom positions use viewport fractions; automatic panels reflow after a resize. Writes are atomic, debounced, and flushed on GUI close and client shutdown. Missing fields keep defaults; invalid individual setting values are ignored. Older panel configs retain their saved positions, and missing categories receive automatic defaults.

Add a `Module` subclass, give it a category and typed settings using `add(...)`, then add **one** `register(new YourModule())` line in `ModuleManager`. Ticks, settings persistence, and category routing are automatic. `group(...)` starts a collapsible subsection. AutoTotem still uses one inventory SWAP with button 40, respecting its container, health, and delay checks.

## Rendering and verification

GUI panels, controls, borders, tooltips, and scrollbars use the existing derivative-antialiased rounded-box SDF. Blur is captured once per frame, restored outside panels, and sampled through rounded masks. Mouse coordinates and text snapping share the same transform. Each panel owns a render stratum. Captures are reused until resize and released on screen close.

Particles use procedural antialiased SDF geometry, with no texture atlas. All particles/trail samples are pooled. Fixed 50 ms simulation plus interpolated position/rotation makes motion independent of rendering FPS. Distance/frustum/alpha culling and a hard 4,096-particle cap bound work. Camera-relative vertices are built during Fabric's extraction phase; reused CPU buffers and rotating GPU buffers draw the whole effect in at most two passes (additive light and normal alpha). Depth testing is enabled unless Through walls is selected; particles do not write depth. Normal particles are sorted back to front. GPU/CPU buffers are released on disable, disconnect, world change, and shutdown; Minecraft owns registered shader pipelines and shared index buffers.

`./gradlew build` runs setting/config-preset, particle-math/pooling, AutoTotem slot-search, panel-layout, and GUI-transform regression tests. Hardware performance, multiplayer packet behavior, Retina sharpness, and visual compatibility with shader/renderer mods require in-game testing. See [TESTING.md](TESTING.md) for the checklist and changed-file map.
