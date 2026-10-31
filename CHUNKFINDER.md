# ChunkFinder inside Basefinding

This checkout had a Basefinding category but no Basefinding module or existing detection log. The implementation adds the missing Basefinding container and its shared JSON log. ChunkFinder is a section of that module, with the ordinary module enable switch and keybind; it is not independently registered. All controls use the existing profile codec and animated glass ClickGUI, including alpha color pickers and collapsible groups.

## Vanilla 1.21.11 baseline

Verified against Loom's decompiled, official Mojang-mapped **1.21.11** sources, rather than assuming a per-face fill probability:

| Source | Verified behavior |
| --- | --- |
| `CaveFeatures.bootstrap`, `AMETHYST_GEODE` | Inner amethyst, alternate budding amethyst, calcite middle shell, smooth basalt outer shell. Alternate inner-layer chance **0.083**; potential placement chance **0.35**; placements require the alternate inner layer. Inner placements are small, medium, large and cluster, each once in the list. Generation offsets are **-16 through +16**. |
| `GeodeFeature.place` | For a selected budding block, choose **one** stage uniformly, visit the six directions in order and place on the **first** growable face, then break. Multiple usable faces do not receive independent placement rolls. |
| `CavePlacements.bootstrap`, `AMETHYST_GEODE` | Once per 24 chunks on average; uniform origin Y from **aboveBottom(6)** through **absolute(30)**. In the standard Overworld that is **-58 through 30**. |
| `BuddingAmethystBlock.randomTick`, `canClusterGrowAtState` | 1/5 growth attempt on a random direction. Empty growth accepts air or water with fluid amount 8. Existing stages must face away from their budding support. |
| `LevelHeightAccessor` | `getMaxY()` is inclusive in this version; snapshot and render bounds account for that. |

Let `E` be the number of usable faces including correctly oriented existing buds/clusters, and `B` the number of budding blocks with at least one such face. The natural baselines are **0.35 × B/E** for total fill and **0.0875 × B/E** for clusters. The 0.083 inner-layer chance determines how many budding blocks occur; it is not multiplied into a statistic already conditioned on finding budding blocks. These are conservative heuristic expectations; interacting placements and correlated faces prevent treating the score as an exact statistical probability. Servers with custom world generation can adjust the baseline controls.

## Default scoring

| Signal | Weight | Reason |
| --- | ---: | --- |
| Mature-cluster ratio above baseline | 60 | Main evidence of sustained random ticking. |
| Total-fill ratio above baseline | 15 | Supports cases where most exposed faces have grown something. |
| Fill anomaly above baseline plus two estimated standard deviations | 15 | Adds evidence for unusually filled geodes while discounting ordinary variation. |
| Nearby man-made blocks | 5 | Weak corroboration: villages, dungeons, natural obsidian and other structures can produce the same blocks. |
| Distinct overgrown geodes in adjacent chunks | 5 | Corroboration capped at two distinct neighbors; the same geode crossing chunk borders cannot bonus itself. |

Ratios are scaled above their respective baselines. The whole score is multiplied by `min(1, E/24)` to discount tiny samples, then clamped to 0–100. A geode with zero usable faces contributes nothing. Chunk score is its strongest geode score. Confidence is Low below 60, Medium from 60, High from 80. The default detection threshold is 60; minimum display confidence defaults to Low so lowering the threshold works naturally.

Other defaults: 1 ms/tick snapshot budget, one analysis worker, an additional 25% of the snapshot budget for scoring slices, evidence radius 4, no spawn exclusion, render range 256, columns from Y=-64 through Y=48, fill alpha multiplier 0.10, 1.5-pixel soft outlines, and 0.4-second fades. Labels, chat and animated toasts start enabled; tracers, geode beams and the color pulse start disabled. Rendering is capped at the nearest 256 selected detections, with up to 256 additional fading entries.

## Scanning and saved data

- Client chunk load and full chunk replacement packets enqueue scans. Both individual block packets and section block update packets reach the existing `LevelChunk.setBlockState` mixin, which invalidates affected snapshots. Changes above the snapshot's Y band do not restart scans.
- A chunk section's palette must contain budding amethyst before any block in the target chunk is searched. Rejected chunks need no volume copy. Enabling the module also discovers already received chunks, starting near the player.
- The client thread copies small slices into a private byte volume under the tick budget. A single worker performs connectivity and neighbor analysis using only that byte volume. It never accesses live Minecraft world objects. All nine source chunk references and revisions must still agree before accepting a result. Unload, dimension switch, disable and disconnect invalidate pending work.
- A 3×3 chunk snapshot groups six-connected budding/amethyst/calcite blocks into a geode. The vertical range is clamped to the dimension's build height and includes vanilla generation offsets plus a closure margin. Missing chunks or a shell continuing beyond the snapshot defer that geode rather than scoring a fragment. Very large connected calcite formations or extensively mined/disconnected shells may therefore be missed or split. Analysis remains limited to chunks actually sent by the server.
- Live observations are removed on unload. Historical detections, scores and annotations remain in the saved log, and remain eligible to render within the configured display distance. Reobserving the same location preserves first-seen time and notes; last-seen time tracks a completed observation. Scoring changes also recalculate saved evidence.
- Files live under `config/scrimmy/basefinding/<SHA-256 server identity>/<SHA-256 dimension>.json`. Singleplayer uses the normalized world directory as its identity. Multiplayer uses the server address. Each file includes the readable identity and dimension, chunk/geode coordinates, all stage counts, score, confidence, UTC epoch-millisecond timestamps, notes, checked and false-positive flags. A 500 ms debounce and atomic replacement keep file work off the client thread; changing world/server flushes pending writes. Exports go under `config/scrimmy/basefinding/exports/`.
- The shared log provides copy coordinates, clear one/all, export, notes, checked and false-positive actions. Checked/false-positive entries are retained but hidden. Clearing is not an ignore rule: a later fresh observation can detect that location again. There is a 10,000-entry saved-log cap. Invalid rows are skipped; an unreadable whole file is backed up before replacement.

## In-game testing

1. Install the built mod with Fabric Loader 0.19.5+ and Fabric API 0.141.6+1.21.11 using Java 25. Join a disposable creative Overworld. Open Right Shift → Basefinding → expand the **Basefinding module**, turn it on, and leave **Enable ChunkFinder** on. Hover that control for the render-distance note. Enable debug logging for detailed statistics.
2. Compare a newly generated geode with an overgrown one. For a repeatable fixture, place a connected amethyst/calcite platform around Y=-20, put at least 12 budding blocks on it three blocks apart, and put correctly facing mature clusters on their five free faces. A 12-block, 60-cluster fixture with a nearby chest scores about **93.94/100**. Empty exposed faces with only the chest score **5/100**. A natural mixture of the four stages should remain quiet. A one-block sample should be discounted.
3. Make the platform cross X=16. Expect one complete geode statistic with the same anchor in both chunks and no self-neighbor bonus. Break/change clusters while watching the client log; individual and `/fill` section updates must trigger fresh statistics. Place/remove a nearby chest and change the evidence radius.
4. Check the full 16×16 translucent boxes, alpha colors, outlines, pulse, label, tracer and beam toggles. Look toward the geode to see its projected label; it scales and fades with distance. Change the Y range, display distance and minimum confidence. Toggle the module off/on and watch the geometry fade.
5. Set Minecraft's render distance low and leave a detected chunk far enough to unload while staying within ChunkFinder's display distance. The saved box should remain. Return, then disconnect/rejoin and switch between worlds/servers. Overworld detections should reload only in their original context; Nether and End must not scan or display them.
6. Open **Open shared Basefinding log**. Select a row, copy coordinates, edit notes, mark checked/false positive and unmark it, clear one, export, and clear all. At small window/GUI sizes the log paginates. Restart and confirm notes, hidden flags, settings, colors including alpha, group state and module keybind survive.
7. Test spawn exclusion and changed scoring weights, then reset defaults. Stress rapid chunk movement, disconnect during a scan, and dense geode fixtures while monitoring frame times on your GPU. The tick budget bounds snapshot/scoring slices; it is not a guarantee of a particular FPS or third-party shader compatibility.

## Verification

Java 25 `./gradlew build` passes **32 tests**. The added tests cover worldgen-normalized scoring, sample discounting, weight bounds, confidence thresholds, stage orientation, palette-restricted search seeds, cross-chunk connectivity, incomplete snapshots, evidence radius, atomic/debounced persistence, server/dimension separation, annotations, exports, invalid entries, and profile/color round trips.

A temporary Fabric smoke mod (outside the deliverable sources) launched Minecraft 1.21.11 with software OpenGL. It exercised an empty 12-block geode, mature growth packet updates, two 93.94-score chunk detections, outlines/fill/tracers/beams/pulse, the glass ClickGUI/log screen, persistence after unload, Nether exclusion, Overworld restoration and hidden checked detections. Additional smoke checks verified enabling over already loaded, grown chunks; native copy/checked/false-positive log actions; readable nonoverlapping labels; and saved boxes remaining visible beyond the chunk load distance. Screenshots and the completed smoke log are included in the delivery artifacts. This validates the launch/API/render path, not hardware FPS.

## Added and changed files

Added:

- `src/main/java/com/scrimmy/client/basefinding/GeodeScoring.java`
- `src/main/java/com/scrimmy/client/basefinding/GeodeAnalysis.java`
- `src/main/java/com/scrimmy/client/basefinding/DetectionLog.java`
- `src/client/java/com/scrimmy/client/basefinding/ChunkFinderScanner.java`
- `src/client/java/com/scrimmy/client/module/basefinding/Basefinding.java`
- `src/client/java/com/scrimmy/client/render/ChunkFinderRenderer.java`
- `src/client/java/com/scrimmy/client/gui/BasefindingLogScreen.java`
- `src/test/java/com/scrimmy/client/basefinding/GeodeScoringTest.java`
- `src/test/java/com/scrimmy/client/basefinding/GeodeAnalysisTest.java`
- `src/test/java/com/scrimmy/client/basefinding/DetectionLogTest.java`
- `src/test/java/com/scrimmy/client/module/BasefindingSettingsTest.java`
- `CHUNKFINDER.md`

Changed:

- `src/client/java/com/scrimmy/client/module/ModuleManager.java`: register Basefinding.
- `src/client/java/com/scrimmy/client/mixin/ClientPacketListenerMixin.java`: invalidate full chunk replacement snapshots.
- `src/client/java/com/scrimmy/client/mixin/LevelChunkMixin.java`: invalidate block/section update snapshots.
- `src/main/java/com/scrimmy/client/setting/Setting.java`: optional descriptive tooltips.
- `src/client/java/com/scrimmy/client/gui/ClickGui.java`: render descriptive tooltips with wrapping.
