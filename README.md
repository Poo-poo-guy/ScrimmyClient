# Scrimmy Client

Scrimmy Client is a Fabric client mod for Minecraft Java Edition 1.21.11. It currently includes AutoTotem and a configurable ClickGUI.

## Build and run on macOS

Install a Java 25 JDK, then check that `java -version` reports 25. From the repository root run:

```sh
./gradlew build
./gradlew runClient
```

Gradle downloads Fabric, Minecraft, and the required assets on the first run. The packaged mod is written to `build/libs/scrimmyclient-1.0.0.jar`; install the matching Fabric Loader and Fabric API to use it outside the development launcher.

In a world, press **Right Shift** to open the ClickGUI. Drag panels by their headers. Left click AutoTotem to toggle it and right click the row to expand its settings. Scroll inside a panel to reach more controls. The Appearance panel changes the theme immediately. The GUI key and each module key can be rebound there. Escape clears a module keybind while the keybind editor is active. Escape closes the GUI otherwise.

AutoTotem uses one inventory click to swap an inventory totem with the offhand. It checks for a totem already in the offhand, an open container, the configured health condition, and its click delay before trying a swap.

## Configuration and extension

Fabric's config directory contains `scrimmy-client.json`. It saves the active profile name, theme, GUI key, module enabled states, settings, and module keybinds. Changes save atomically after a short pause in input and when the GUI closes. Missing or corrupt files load defaults. The Appearance panel has a reset button. The file format has a `profiles` object for future named profiles; only `default` is active today.

To add a module, create one `Module` subclass in `src/client/java/com/scrimmy/client/module`, declare typed settings with `add(...)`, and add one `register(new YourModule())` line to `ModuleManager`. Client ticks are delivered automatically.

## Rendering

The ClickGUI uses Minecraft 1.21.11's built-in post blur and temporarily applies the theme's blur strength to Minecraft's menu blur option while the GUI is open. It restores the prior option value on close. The blur is across the game background; Minecraft's GUI render state exposes one blur boundary per frame, so this client does not apply a separate blur pass inside every rounded panel.

Rounded panels and controls use a CPU alpha coverage renderer with separately supported corner radii, borders, glow, and shadow. It avoids hard rectangular clipping at corners by keeping content inset. This is a fallback to a custom SDF shader: it is less precise than a physical-pixel shader on Retina/HiDPI displays and may cost more draw calls. GUI scale, radius, opacity, colors, and animation controls update live. The project does not yet include a HUD or additional modules.

The automated test suite covers setting validation and AutoTotem slot priority. Graphical interaction, frame rate, and macOS GPU behavior require testing on a Mac with a display.
