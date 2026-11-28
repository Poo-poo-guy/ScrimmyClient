# ChunkFinder V2

Enable **ChunkFinder V2** beside ChunkFinder in the Basefinding ClickGUI panel.
The two modules are independent. V2 never enables, retunes or replaces another module.
All production code is in `src/client`; there are no new synced registrations or network calls.

## Use

- The north-up glass grid colors scored chunks within the scan radius. White marks your chunk.
- Optional world outlines and the top N glass nametags use the same primitives as ChunkFinder.
- Minimum score filters displays and the ranked list, but not CSV exports.
- The inline ClickGUI list shows five best chunks. Open **Ranked chunks / drill target** for the full paginated list and raw evidence on hover.
- **Send to DrillTrail** sets a local chunk destination. Gold marks it in the grid/world, with coordinates and distance on the HUD. The list can clear it. It does not enable DrillTrail or automate movement/drilling.
- B marks the current chunk **base**; N marks it **not base**. Keys work in gameplay while V2 is enabled and can be rebound or cleared under **Marks and export**. The same actions are available as buttons.
- **Export feature CSV** writes the current scored snapshot to `<game directory>/scrimmy/chunkfinder-v2/exports/`. During initial scans the snapshot may be partial; the full list shows scored/loaded counts.
- Marks live in `<game directory>/scrimmy/chunkfinder-v2/<scope>-marks.csv`. The scope hashes server/save identity and dimension. Marks survive relogs and remain separate across worlds and dimensions.

## Evidence and scoring

Only already-loaded client chunk data is used. "Rendered" evidence means a loaded block with a face adjacent to known air, water or lava, including transparent/non-occluding natural blocks. Unknown neighboring chunks never fabricate exposure. Camera line of sight is not required.

- Built blocks receive rarity 2 for ambiguous structure materials (planks, bricks, rails, torches and similar) or 5 for stronger materials (glass, concrete, signs, beds and redstone components). Generated structures can also contain these blocks.
- `DrillTrailScanner` supplies its unmodified Smart shaft/tunnel samples. V2 merges its edge-only updates using the detector's existing `inEdges` rule. A sample is a detected center, not a whole tunnel or a meter count.
- New fragment detection collects exposed blocks within eight blocks transversely of these samples. It excludes the union of the 3x3 holes and their one-block wall rings, then groups blocks by six-face connectivity. It records whole connected fragment size/non-natural count, including across loaded chunk borders. Each chunk's raw leak totals count only its own blocks so a border crossing cannot multiply the score. The CSV's fragment column uses `size:non-natural;size:non-natural`.
- `ChunkFinderScanner` supplies its unmodified geode analysis. The amethyst feature is the maximum existing `BaseLikelihood.growth` value in that chunk; it is zero outside the Overworld.
- Surface evidence finds level 5x5 patches, straight two-level terraces, dirt/cobble columns at least four blocks tall with three exposed sides, and cobble/obsidian columns at least four blocks tall beside fluid or eight blocks tall when dry. Submerged flat patches are excluded.

Each of the five feature groups has one weight (zero disables that contribution). Surface evidence is weak by default because natural terrain can resemble construction. The raw score is:

```
evidence = builtWeight * exposedRarity / 40
         + tunnelWeight * tunnelSamples / 24
         + leakWeight * (leakedBlocks / 128 + leakedRarity / 20)
         + growthWeight * amethystGrowth
         + surfaceWeight * (flattened + terraces + 4*pillars + 2*casts) / 32
rawScore = 100 * (1 - exp(-max(0, evidence)))
smoothedScore = (1 - neighborShare) * rawScore
              + neighborShare * sum(eight neighbors' rawScores) / 8
```

Unloaded/unscored neighbors contribute zero; no scores are invented for unloaded chunks. These are human-activity heuristics, not probabilities or proof that a base exists. Labels are tuning data and do not alter scoring.

## Reuse, lifecycle and performance

`ChunkFinderScanner` and `DrillTrailScanner` have additional callback constructors. Their original constructors delegate to the same module settings and callbacks as before. ChunkFinder retains its existing periodic refresh; the V2 consumer disables periodic refresh so unchanged chunk data stays cached. Neither detection algorithm was copied or changed.

DrillTrail previously had no destination concept. The only hook added to it is the immutable `DrillTarget(x,z)` plus `setDrillTarget`, `drillTarget` and `clearDrillTarget`. Its existing session reset also clears that target. The hook does not change its path network, visits, settings or enabled state; V2 displays the destination.

Snapshots and discovery are incremental on ticks. Terrain copying uses 128-block slices under a 0.75ms tick budget; the reused scanners retain their original bounded snapshot budgets and worker analysis. Feature analysis, fragment grouping, scoring, CSV writes and mark persistence run off the client thread. Each scanner/scoring worker accepts one analysis task at a time; local file writes are serialized. Weight-only changes reuse cached raw features. Block/section changes, full chunk refreshes, neighbor halo changes, loads and unloads invalidate affected snapshots. Results are revision checked, pruned on unload/radius changes and cleared on session changes. No periodic terrain rescans occur. Shutdown gives local writes up to five seconds to finish.

## Validation

Use Java 25 and `./gradlew build`. Regression tests cover the original modules as well as known/unknown face exposure, surfaces, shaft wall exclusion, cross-border fragments, smoothing, normalization, destination storage, settings, server/dimension isolation, CSV fields, early-label writes and corrupted-file preservation. Interactive rendering and frame timing still need an in-game check on the user's renderer/server data.
