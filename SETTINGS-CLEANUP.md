# Module settings cleanup

The module row is the main enable switch and appears before its controls. Kept controls use the same config keys, except for the migrations below. Every control has a plain-language tooltip. Nested settings groups have been removed because the shorter lists no longer need them.

| Module | Controls kept (besides Enabled) | Removed or merged, and why |
| --- | --- | --- |
| Basefinding / Chunk Finder | Show Geodes, Highlight Chunks, Nametags, Minimum Score, Range | Replaced scoring weights, confidence filters, worldgen baselines, evidence toggles/radius, and spawn exclusion with the new loaded-area model. Merged fill/outline into Highlight Chunks. Fixed scan budget, column geometry, outline thickness, opacity, fades, tracers and beams to a consistent style. Removed individual confidence/outline/label/pulse colors in favor of the theme. Chat and toast notifications retain their default behavior. Removed log/export/clear/reset actions from the settings list; historical log data and its reader/export APIs remain compatible. |
| AutoTotem | Mode, Health threshold, Delay, Inventory closed only | Fixed hotbar-first search and a steady swap delay at their existing defaults; search ordering and random delay did not need separate controls. |
| HitParticles | Style, Amount, Trigger, Targets, Through walls, Size | The six built-in styles now own shape, motion, lifetime/speed ranges, randomization, colors/gradients, glow, trails, easing, blending, sound and other fine tuning. Removed per-shape colors, separate alpha/outline/pulse controls, particle caps and render/spawn details. Removed user-slot save/load controls and the Custom selector; they duplicated style selection and exposed the removed tuning. Damage confirmation, target filtering and particle simulation remain unchanged. |
| BlockESP | Blocks, Extra blocks, Range, Style, Through walls, Tracers | Merged seven category switches into Blocks, including an empty selection. Extra blocks retains the custom block list and its colors. Fixed scan budget, match cap, line widths, fill/hidden/tracer opacity, fading, pulse, target hiding and tracer cap to existing defaults. Removed multiple color sources, category/per-block overrides and color animation; default category colors remain. |
| AntiVanish | Range, Warning delay, Missing from tab, Sound | Fixed head size and spin at their existing defaults. Removed the one-shot test switch; normal detection and warnings remain unchanged. |
| LogoutGhosts | Duration, Out of range, Chat messages, Sound, Coordinates, Range | Fixed marker cap, distance fade, body/beam/head/label parts, color and individual sizes at their existing defaults. Removed the test marker and separate vanish-chat switch (default off). Tracking and departure notifications remain unchanged. |
| Freecam | Speed, Sprint speed multiplier | Both existing controls are useful and remain. Removed the unnecessary Movement group; camera movement and input handling are unchanged. |

## Config migration

- Old unknown/removed settings are ignored, including malformed values. Saved configs contain only the retained controls.
- Chunk Finder converts `cfDistance` from blocks to `cfRange` in chunks, retains `cfThreshold` and `cfLabels`, and combines the old fill/outline switches. The former `cfEnabled` gate is folded into the module's enable state. New keys take precedence over old aliases.
- BlockESP converts `group0` through `group6` to `blockGroups`, preserving an explicitly empty selection.
- HitParticles maps an old Custom look to the closest built-in style from its old shape/motion, and restores retained amount, size, target/trigger and visibility values. Adjusting amount/size keeps the chosen style across restarts. Removed fine tuning and user preset slots are ignored.
- Old Basefinding geode logs are still readable. Historical entries are kept separate from live scoring so unloaded or old evidence cannot leave a stale primary result.
