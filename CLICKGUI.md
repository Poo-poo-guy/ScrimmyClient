# ClickGUI appearance and motion

The existing five category panels, module settings, keybinds, panel positions, and `ScrimmyClient+` entry remain in the existing `ClickGui` and config architecture. Module scanning/combat/world behavior is unchanged. The changes in module files only rename ChunkFinder and select the font for messages.

## Files and extension points

- `theme/Theme.java`: persisted Style, Animation, Animation Speed (0.5–2), and Font. Existing appearance controls remain. Old `easing` preferences map to Smooth or Spring.
- `animation/Animation.java`, `Easing.java`, `AnimationClock.java`, `AnimatedColor.java`: reversible eased timelines, analytic damped springs, a shared render-frame clock, and fractional color channels. No frame-count integration or dropped elapsed time.
- `animation/AnimationStyle.java`: register one motion profile to add an animation option. Numeric motion parameters blend when the option changes; live states retain their position and velocity.
- `theme/UiTokens.java`: shared colors, dimensions, and motion constants. `theme/SurfaceStyle.java` registers background targets; `render/ThemeSurfaces.java` animates them across client surfaces.
- `gui/ClickGui.java`: existing controls now use retained animation states, smooth scrolling/dragging, animated slider values/color channels, setting fades, delayed tooltips, centered Zoom transforms, and matching mouse transforms.
- `render/RoundedRenderer.java`: retains the derivative-antialiased rounded SDF shader and pools shape submissions. `PanelBlur.java` shares one capture per rendered frame across HUD/GUI users and retains targets until resize/reload/shutdown. Solid skips capture and sampling once its fade completes.
- `theme/FontCatalog.java`: register a font family once, then supply matching regular/semibold TTF providers and kerning tables. `render/ClientFonts.java` shares Minecraft 1.21.11's FreeType rasterizer and lazy GPU glyph-atlas cache, prewarms ASCII after font reload, caches bounded text layouts, applies Latin GPOS pair kerning, cross-fades font families, and measures the same layouts it draws. Notes fields use the selected provider with vanilla caret/selection handling.
- `mixin/GameRendererMixin.java`: samples the UI clock and starts pooled rendering once per frame. `FontAccessor.java` gives text-entry widgets the selected TrueType provider. `ScrimmyClient.java` registers reload/shutdown cleanup after Minecraft's font reloader.
- Client renderers route their text through `ClientFonts`, and recompute nametag/notification bounds during font fades.
- `config/ConfigStore.java` and `ModuleSettings.java`: read the old `Basefinding` module entry as `ChunkFinder`, retaining enabled state, keybind, settings, and extra data. The canonical entry wins if both exist. Normal saving writes `ChunkFinder`. The category remains `Basefinding`; the existing detection-storage directory remains readable.

## Fonts

All four families are bundled, with regular (400) and semibold (600) weights. Every family is licensed under the **SIL Open Font License 1.1 (OFL-1.1)**. Copyright notices and complete licenses are in `src/client/resources/assets/scrimmyclient/fonts/<family>/OFL.txt`.

| Family | Upstream source in google/fonts | Bundled form |
| --- | --- | --- |
| Inter | `ofl/inter/Inter[opsz,wght].ttf` | Static 400/600 instances, optical size 14 |
| Poppins | `ofl/poppins/Poppins-Regular.ttf`, `Poppins-SemiBold.ttf` | Original static weights |
| Montserrat | `ofl/montserrat/Montserrat[wght].ttf` | Static 400/600 instances |
| Roboto | `ofl/roboto/Roboto[wdth,wght].ttf` | Static 400/600 instances, width 100 |

Sources: https://github.com/google/fonts/tree/main/ofl . `scripts/build_font_kerning.py` regenerates cached Latin pair adjustments from the bundled fonts using fonttools. Poppins supplies no GPOS pair adjustments. Providers use 8× raster oversampling, cached by Minecraft rather than rebuilt per frame. Characters absent from a bundled font fall back to Minecraft's Unicode providers.

## Animation styles

- **Smooth**: soft quintic fade/slide reveals with critically damped controls.
- **Spring**: restrained back-eased reveals and analytic springs with less than 1% state overshoot.
- **Cascade**: cubic panel and row waves with reversed closing order.
- **Zoom**: centered 92–100% scale/fade, with a matching blur ramp.

## Validation and practical limits

Run `./gradlew build` on Java 25. The verified build passes 106 tests. Regression tests cover analytic motion at 60/240 FPS, long frames, position/velocity continuity on reversal, stagger timing, persisted appearance settings, and old/new module-key precedence, alongside the existing module suite.

Live smoke checks used Minecraft 1.21.11 under Xvfb/Mesa software rendering: a local world join, GUI open/close, panel drag, repeated Glass/Solid changes, all four animation selections, all four font selections, both speed limits, category/module collapse, scrolling, interrupted open/close reversal, config persistence, and resource reload in both Solid and Glass. Authentication-service network errors are environmental; the test world uses the development offline player.

Minecraft owns glyph atlas resource lifetime and the underlying GUI text/scissor submissions; these still use vanilla allocation paths. Client shape and animation state is retained, layouts are cached, and updates allocate only when text/layout/resources change or caches warm. Latin pair kerning is supported; complex-script shaping and caret kerning in the vanilla notes field follow Minecraft's renderer. 8× raster oversampling has a finite resolution; extreme external magnification can soften text. GPU FPS, high-DPI quality, and third-party shader/mod compatibility require hardware testing.
