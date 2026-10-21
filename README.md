# Scrimmy Client

Scrimmy Client is a Fabric client mod for Minecraft Java Edition 1.21.11. It currently includes AutoTotem and a configurable ClickGUI.

## Build and run on macOS

Install a Java 25 JDK, then check that `java -version` reports 25. From the repository root run:

```sh
./gradlew build
./gradlew runClient
```

Gradle downloads Fabric, Minecraft, and the required assets on the first run. The packaged mod is written to `build/libs/scrimmyclient-1.0.0.jar`; install the matching Fabric Loader and Fabric API to use it outside the development launcher.

In a world, press **Right Shift** to open the ClickGUI. Its four panels are Combat, Render, Client, and Basefinding. Drag a header to move a panel; right click the header or click its +/- control to collapse it. Clicking a panel brings it to the front. Left click AutoTotem to toggle it and right click the row to expand its settings. Scroll inside a panel to reach more controls. Open **ScrimmyClient+** inside Client for live theme controls, including the ClickGUI scale multiplier, GUI key, and reset button. Escape clears a module keybind while the keybind editor is active. Escape closes the GUI otherwise.

AutoTotem uses one inventory click to swap an inventory totem with the offhand. It checks for a totem already in the offhand, an open container, the configured health condition, and its click delay before trying a swap.

## Configuration and extension

Fabric's config directory contains `scrimmy-client.json`. It saves the active profile name, theme, GUI key, module enabled states, settings, module keybinds, and category panel positions and open/closed states. Panel positions use viewport fractions so the layout adapts to window and GUI scale changes. Changes save atomically after a short pause in input and when the GUI closes. Missing or corrupt files load defaults. Older configs without panel layouts keep their existing theme and modules and use default panel positions. The file format has a `profiles` object for future named profiles; only `default` is active today.

To add a module, create one `Module` subclass in `src/client/java/com/scrimmy/client/module`, pass its category to the base constructor, declare typed settings with `add(...)`, and add one `register(new YourModule())` line to `ModuleManager`. Category routing and client ticks are automatic.

## Rendering

Rounded panels, headers, controls, outlines, shadows, and tooltips use an SDF rounded-box shader through Minecraft 1.21.11's GUI render-state API. Derivative-based coverage follows one framebuffer pixel, including Minecraft GUI scale and the additional ClickGUI multiplier. Text origins are snapped to framebuffer pixels. Narrow viewports reduce panel width to keep the four default panels visible; clipped labels have hover tooltips.

The background is captured and blurred once per frame with Minecraft's post effect, then the original scene is restored. Each panel samples that blurred scene through its SDF mask, keeping everything outside the rounded panel sharp. The two capture textures are reused and resized with the framebuffer and disposed when the screen closes. Minecraft owns the shared pipelines and samplers. Blur strength temporarily uses the game's menu blur option and restores its prior value on close.

The automated test suite covers setting validation, AutoTotem slot priority, scale transforms, stable slider drags, and safe panel positions. macOS/Retina GPU behavior and frame rate still require testing on a Mac with a display.
