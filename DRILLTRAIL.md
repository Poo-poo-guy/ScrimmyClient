# DrillTrail — Fabric 1.21.11

Enable **Right Shift → Basefinding → DrillTrail** to follow regular 3×3 tunnels. The module row is Enabled; its only additional controls are **Range**, **Dim Visited**, **Mark Leads**, **Show Direction**, **Hint HUD**, and **Minimum Length**. Each has a tooltip. Colors, fades and glass styling use the client theme.

## Detection and paths

`DrillTrailScanner` discovers loaded chunks in a center-first order. Only newly observed chunks and chunks invalidated by load/unload/block changes enter the work queue. Minecraft data is copied on client ticks under a fixed 1 ms deadline; an eight-block halo supplies walls, intersection rays and hazard context. Completely solid and completely open target sections are skipped. Immutable byte snapshots are analyzed on a bounded worker; no worker reads the live world.

`TunnelAnalysis` checks 3×3 sections perpendicular to X, Y and Z. A section needs eight air blocks out of nine and at least ten solid wall blocks out of twelve around its perimeter. This permits one piece of gravel, water, lava or a placed block without losing the path. Regular sections are joined across chunk borders and one-block stair/diagonal steps.

`TunnelNetwork` joins short openings using independently verified air rays, sharing a junction where rays meet. It splits the graph into paths at branches and retains closed loops. Tiny side spurs at stair lips/end caps are suppressed. Components need both the selected length and enough regular sections; open space alone cannot manufacture a long tunnel. Spline sampling smooths corners before rendering.

Detection constants are at the top of `TunnelAnalysis` and `TunnelNetwork`, including the default minimum length (12), one-block tolerance, wall support, six-block intersection bridge, seven-block drop check and four-block minimum branch span. These are internal defaults, not extra settings.

## Trail, leads and HUD

The shared `WorldOverlayRenderer` was extracted from Chunk Finder's existing shader, line clipping, anti-aliased ribbons and reusable CPU/GPU buffers. Both modules now use it. DrillTrail layers a soft glow around the theme-accent line, ignores terrain depth and fades the far part of the trail. The optional moving pulse flows from the nearer/visited end toward unexplored sections. The distance fade and animation constants live at the top of `DrillTrailRenderer`.

Passing within 2.25 blocks of a center-line point records a visit. Visited segments ease toward a dimmer appearance. Diamond markers identify ends; crosses identify junctions; larger double diamonds identify room openings. Unknown boundaries become Unexplored leads. Exclamation markers warn about known lava, deep drops and dangerous/obstructing blocks inside or immediately ahead of a trail. Warnings remain on when Mark Leads is off.

The glass HUD pill points to the nearest unvisited lead within Range. It includes the lead type, distance, relative direction, compass bearing and up/down hints. It reuses `RoundedRenderer.ReusablePanel`, `PanelBlur`, the client font and theme animation/tint/opacity/radius. Disabling it or losing the lead fades it away.

When Chunk Finder is active, `Basefinding.scoreAtChunk(x, z)` supplies a read-only relative score in constant time. Scores of 75+ brighten that trail section. DrillTrail does not enable or change Chunk Finder and continues working when it is off.

## Session memory and bounds

Unloading a chunk keeps its last trail and visited state. Rescanning a changed/reloaded chunk replaces that chunk's evidence. Changing dimensions, worlds or servers clears all history, including while DrillTrail is disabled. Disconnect event handling has a client-tick guard for teardown paths that clear the level after the connection event.

History is session-only: it is not written to disk or kept across restarts. To bound memory, at most 2,048 chunk results and 65,536 regular samples are retained; the oldest chunk results are evicted beyond these limits. Worker connector/ray maps are bounded as well. Rendering prepares at most 8,192 nearby spline sections and 48 markers, with bounded retiring visuals for smooth fades. Frame extraction reuses marker arrays, strings, native vertex buffers, matrices and panels; Minecraft still manages its internal render submissions.

## Limits

- This detects tunnel geometry, not the shard drill item. Naturally regular caves and other constructed 3×3 passages can match.
- Only server-loaded data can be scanned. A short visible fragment may not meet Minimum Length until more chunks arrive. Remembered sections remain drawable within Range after unloading, but unknown continuation cannot be predicted.
- A gap wider than six blocks is split into leads rather than connected through an unverified room/cave. Openings and tiny branches can therefore be intentionally omitted or simplified.
- A tunnel with more than one obstruction per section, substantially broken walls or a different cross-section can split or disappear. Hazard warnings use observed blocks; they cannot inspect unreceived terrain or guarantee a safe route.
- High-density networks are capped, and initial discovery is progressive to protect frame time. Hardware performance and third-party renderer/shader compatibility require testing on the target client.

## Verification

Java 25 `./gradlew clean build` passes all 87 regression tests. Pure tests cover all three axes, chunk borders, flat/vertical turns, diagonal stairs, stair lips, crossings, closed loops, rooms, unknown frontiers, short pockets, obstructions, a fully blocked lava section, deep drops, visits, history caps, config round trips and module registration.

A temporary unshipped Fabric dev harness carved tunnels, stairs, a shaft, branches, a room and hazards in a flat singleplayer world. Mesa software OpenGL drew the line/HUD alongside Chunk Finder. The harness checked positive/disabled Chunk Finder scores, unloaded history, setting toggles, disable, disconnect, session clearing and client shutdown. The final clean build excludes the harness and its extra initializer.
