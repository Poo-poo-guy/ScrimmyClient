# Chunk Finder — Fabric 1.21.11

Enable Basefinding to scan loaded Overworld chunks and estimate where a player spent time. The module row is the enable switch. Its five controls are Show Geodes, Highlight Chunks, Nametags, Minimum Score (0–100), and Range (2–32 chunks). Only chunks actually sent by the server can supply evidence.

## Growth and loaded-area scoring

`GeodeAnalysis` groups budding blocks through their amethyst/calcite shell and bridges small gaps by proximity. It follows the whole geode across chunk borders, and waits when an unloaded boundary makes its exposed faces unknown. Each growable face contributes its bud stage: small .25, medium .5, large .75, cluster 1; empty air/source water contributes zero. Blocked faces and buds facing another support are excluded. The weighted sum divided by exposed faces is the geode's growth ratio.

`BaseLikelihood` spreads grown-geode support over a nine-chunk radius with a smooth distance falloff. Ungrown geodes subtract support inside the same radius, narrowing the estimate. Pairwise overlap adds support where multiple grown geodes agree; a geode cannot overlap itself. Border snapshots are deduplicated before voting. Two spatial smoothing passes run before scores are normalized to the best chunk at 100. Exactly one chunk is primary. Empty or entirely ungrown evidence produces no result.

The stage weights, loaded radius, falloff, growth cutoffs, negative weight, overlap bonus and smoothing passes are named constants at the top of `BaseLikelihood`. `BUDDING_LINK_RADIUS` lives at the top of `GeodeAnalysis`. Scores are relative evidence of time loaded, not calibrated probabilities that a base exists. Harvested clusters, naturally placed buds, spawn loading and other chunk loaders can affect the estimate.

## Rendering

Geodes retain their own translucent bounds. Likely chunks use thin filled floor planes with soft outlines, shaded from a cool tone to the theme accent according to score. The single primary chunk has a brighter fill, stronger outline, larger tag and soft glow. Minimum Score filters chunks independently of Show Geodes.

Nametags are projected from each chunk's center into camera-facing glass pills. They show Base chance and `87/100`, or Best estimate for the primary. They reuse the client font, theme tint/accent/opacity/radius/animation speed, shared panel blur and reusable rounded panels. Size eases with distance and stays clamped. Tags outside the screen or overlapping a nearer/primary tag are skipped for readability. All world highlights ignore depth and fade as evidence enters/leaves.

At most 256 highest-scoring chunks and 256 geodes are displayed, with bounded retiring markers for fades. The scanner never requests chunks. Palette filtering and immutable snapshot copying run within a fixed 1 ms tick budget; geode analysis and grid estimation use bounded single-worker jobs. Results are cached, invalidated on chunk/block changes, refreshed periodically, and pruned on movement/unload. Only the sections surrounding budding blocks are copied. No per-frame marker collections, strings, panels or CPU vertex buffers are created; Minecraft's own GUI/GPU submissions still manage their internal frame state.

The saved Basefinding log remains compatible, but historical entries never feed the live grid. See [SETTINGS-CLEANUP.md](SETTINGS-CLEANUP.md) for the complete settings and migration audit.
