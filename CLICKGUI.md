# ClickGUI appearance and motion

The existing five category panels, module settings, keybinds, panel positions, and `ScrimmyClient+` entry remain in the existing `ClickGui` and config architecture. Module scanning/combat/world behavior is unchanged. The changes in module files only rename ChunkFinder and select the font for messages.

## Files and extension points

- `theme/Theme.java`: persisted Style, Animation, Animation Speed (0.5–2), and Font. Existing appearance controls remain. Old `easing` preferences map to Smooth or Spring.
- `animation/Animation.java`, `Easing.java`, `AnimationClock.java`, `AnimatedColor.java`: reversible eased timelines, analytic damped springs, a shared render-frame clock, and fractional color channels. No frame-count integration or dropped elapsed time.
- `animation/AnimationStyle.java`: register one motion profile to add an animation option. Numeric motion parameters blend when the option changes; live states retain their position and velocity.
- `theme/UiTokens.java`: shared colors, dimensions, and motion constants. `theme/SurfaceStyle.java` registers background targets; `render/ThemeSurfaces.java` animates them across client surfaces.
- `gui/ClickGui.java`: existing controls now use retained animation states, smooth scrolling/dragging, animated slider values/color channels, setting fades, delayed tooltips, centered Zoom transforms, and matching mouse transforms.
- `render/RoundedRenderer.java`: retains the derivative-antialiased rounded SDF shader and pools shape submissions. `PanelBlur.java` shares one capture per rendered frame across HUD/GUI users and retains targets until resize/reload/shutdown. Solid skips capture and sampling once its fade completes.
- `theme/FontCatalog.java`: explicit font paths and selectable names; no directory scanning. `render/ClientFonts.java` caches fractional text layouts, runtime GPOS kerning, and cross-fades. `font/FontFace.java` rasterizes outlines with antialiasing and fractional metrics. `font/DistanceField.java` builds exact signed distances with an 8-texel range and 11-texel padding. `render/SdfFonts.java` bakes all families off-thread, uploads one 1-MiB page per frame, caches two viewport generations, and keeps the last atlas visible during replacement. Notes and notifications use the same glyph source through Minecraft's font API.
- `mixin/GameRendererMixin.java`: samples the UI clock and starts pooled rendering once per frame. `FontAccessor.java` gives text-entry widgets the selected TrueType provider; `FontSourceMixin` routes client font IDs to their SDF glyphs and `GlyphSamplerMixin` replaces NEAREST with LINEAR only for those glyphs. `ScrimmyClient.java` registers reload/shutdown cleanup after Minecraft's font reloader.
- Client renderers route their text through `ClientFonts`, and recompute nametag/notification bounds during font fades.
- `config/ConfigStore.java` and `ModuleSettings.java`: read the old `Basefinding` module entry as `ChunkFinder`, retaining enabled state, keybind, settings, and extra data. The canonical entry wins if both exist. Normal saving writes `ChunkFinder`. The category remains `Basefinding`; the existing detection-storage directory remains readable.

## Fonts

The selectable order is **Xuong, Inter, Poppins, Montserrat**; the existing Inter default is preserved. Xuong is the user-supplied `src/main/resources/assets/scrimmyclient/fonts/Xuong.ttf` (font metadata says Bold/700). It is used directly for regular text; titles add only a 0.24-logical-pixel synthetic stroke, with unchanged advances. No second Xuong file is needed, and its exact resource path is loaded explicitly despite the capitalized filename.

Inter, Poppins, and Montserrat are bundled in regular (400) and semibold (600) weights under **SIL Open Font License 1.1 (OFL-1.1)**. Complete licenses remain in `src/client/resources/assets/scrimmyclient/fonts/<family>/OFL.txt`. Xuong is user-provided and is not claimed to carry those fallback licenses. Roboto and the obsolete offline kerning tables/script have been removed.

| Fallback | Upstream source in google/fonts | Bundled form |
| --- | --- | --- |
| Inter | `ofl/inter/Inter[opsz,wght].ttf` | Static 400/600 instances, optical size 14 |
| Poppins | `ofl/poppins/Poppins-Regular.ttf`, `Poppins-SemiBold.ttf` | Original static weights |
| Montserrat | `ofl/montserrat/Montserrat[wght].ttf` | Static 400/600 instances |

Sources: https://github.com/google/fonts/tree/main/ofl . A missing selected-font glyph uses the matching Inter weight per codepoint, with Inter metrics and the same baseline. If neither selected font nor Inter covers a codepoint (for example CJK), Minecraft's Unicode provider is the final fallback so supported characters remain visible.

### Text sampling fix

Minecraft's `GlyphRenderState.textureSetup()` forces NEAREST sampling, and its normal text shader discards alpha below 0.1. Increasing the previous TrueType provider's oversample value alone therefore left jagged steps and clipped faint edges. ClickGUI additionally snapped text origins and rounded scrolling; nametag centers used integer halves.

Client fonts now use a dedicated **LINEAR-filtered signed distance field shader**, with derivative-sized edge coverage and premultiplied RGB/alpha paired with premultiplied blending. This is the SDF alternative to mipmapped bitmap sampling. Grayscale antialiasing and fractional metrics avoid LCD color fringes on translucent glass. SDF glyphs retain transparent padding and a separate atlas gutter, so bilinear taps cannot reach adjacent glyphs.

Glyphs bake at `ceil(3 × Minecraft GUI scale × ClickGUI scale)`, giving approximately **3× on-screen raster density** through ClickGUI's 0.75–1.5 scale range; SDF coverage also handles Zoom and scaled HUD labels. Window dimensions, Minecraft GUI scale, and the ClickGUI scale target select a cached viewport generation; changes bake asynchronously and swap only after GPU upload finishes. Common glyphs for every selectable family/weight are prewarmed together, so font switching does not rebuild atlases. Unseen Unicode glyphs are inserted lazily once and cached. Resource reload disposes old textures and prepares all families again. Fractional panel, scroll, kerning-run, and nametag-center positions reach the GPU without pixel snapping.

## Animation styles

- **Smooth**: soft quintic fade/slide reveals with critically damped controls.
- **Spring**: restrained back-eased reveals and analytic springs with less than 1% state overshoot.
- **Cascade**: cubic panel and row waves with reversed closing order.
- **Zoom**: centered 92–100% scale/fade, with a matching blur ramp.

## Validation and practical limits

Run `./gradlew build` on Java 25. The verified build passes **110 tests**. Tests cover the existing module/motion/config suite plus explicit font resources, Xuong/Inter per-character fallback, synthetic-title advances, signed-distance edge coverage, atlas padding, stable fractional advances across four raster resolutions, and kerning.

Minecraft owns underlying text/scissor submissions and notes-field caret behavior. The Latin/Greek/Cyrillic fonts cannot supply glyphs outside their coverage; final Unicode fallback uses Minecraft's own renderer. Complex-script shaping and bidirectional editing follow Minecraft. Hardware GPU FPS, high-DPI quality, and third-party shader/mod compatibility still require hardware testing.

Live checks used Minecraft 1.21.11 under Xvfb/Mesa: world join, all four font selections, Glass/Solid rendering, resource reload, window resizing, Minecraft GUI scales 1 and 2, and ClickGUI scale changes across 0.75–1.5. Hardware GPU/high-DPI verification remains separate.
