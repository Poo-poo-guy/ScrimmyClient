# DrillTrail — Fabric 1.21.11

Enable **Right Shift → Basefinding → DrillTrail**. The module row controls Enabled. Its only other controls, all with tooltips, are **Mode**, **Range**, **Minimum Length**, **Dim Visited**, **Show Direction**, and **Nametags**. Mode defaults to **Smart**. Old Mark Leads/Hint HUD fields are ignored by the existing config codec; missing or invalid modes use Smart. Theme controls supply colors, glass tint, blur, borders, corner radius and animation speed.

## Modes and shared detection

Normal uses clean, fully open 3×3 sections with at least ten supporting wall cells out of twelve. The shared graph follows straight passages, simple turns, vertical passages and stairs. Normal draws the smooth trail, optional direction pulse, visited dimming and optional nametags. It skips plug analysis, joint tolerance, hazards, lead markers, HUD hints and Chunk Finder reads.

Smart runs that same detector and graph builder with extra checks. It preserves the previous allowance for one obstructing cell, and recognizes locally supported overlapping orientations at frequent corners. Relaxed wall support must establish a nearby joint; a broad three-block-high room cannot qualify just by having a long wall. Local thinning removes duplicate paths around overlapping joints without collapsing genuine tunnel loops. Component lengths and Minimum Length use distance along graph edges, including turns and stairs.

At a blocked path end, Smart verifies a flat row, column, or full 3×3 solid slab, with up to two blocked layers. Any ordinary solid material qualifies. Two aligned sections establish the recent heading; the same cross-section must resume at the same offset and continue beyond the blockage. Scattered cells, fluids, misaligned continuations and thicker blockages are rejected. Only endpoint connections are bridged. Bridged portions are dimmed and get a distinct small gate marker and Plug lead; a successful bridge does not leave a false dead-end marker.

Smart also retains junction crosses, end diamonds, larger double-diamond room markers, unknown frontiers, hazard warnings and the glass lead HUD. Room openings require a broad air section on consecutive layers, so ordinary corner and staircase joints do not become rooms. Lava, deep drops and dangerous blocks are checked inside and immediately ahead of detected sections. `Basefinding.scoreAtChunk(x,z)` remains an optional, read-only integration: scores of 75+ brighten the local trail. DrillTrail does not enable or change Chunk Finder.

## Rescanning and rendering

- Chunk loads, replacement packets and actual block changes invalidate evidence. Unchanged chunks stay cached. Neighbors only reanalyze the strips whose cross-section, ray or hazard halo can see the changed chunk; interior evidence is merged back unchanged. Repeated invalidations coalesce.
- Section palettes reject fully solid and fully air target sections. Uniform source sections avoid repeated state decoding. Air and solid row bitmaps are packed once while snapshotting and used for cross-section/wall checks.
- A three-block lattice finds seeds; verified hits follow nearby sections and short air rays through bends and stairs. Detection and graph construction run on separate single-worker queues using immutable snapshots. Workers never access Minecraft's world.
- Snapshot work yields between bounded slices. The tick budget adjusts between 0.2 and 2 ms in response to tick spacing. Nearer chunks, with a bias toward movement, run first. Revision and chunk-identity checks reject stale results.
- Changed graph evidence can be chained every two ticks instead of every ten. Rendering caches spline samples by edge and neighboring control points; visits, fades, range changes and Chunk Finder scores update appearance without rebuilding unchanged curves. Smart smooths frequent joints more strongly, with bounded curves inside the corridor around verified centers.
- Frame extraction reuses segment/tag arrays, projection scratch storage, overlap bounds, matrices and the existing `WorldOverlayRenderer` CPU/GPU buffers. Label text, widths, sorting and cache maintenance happen on ticks. Minecraft's render submissions and first-use GPU setup still have their own allocations.

## Nametags and memory

One connected tunnel receives one tag, including when its render paths split at junctions. Two lines show **DrillTrail** and its walked length, such as **142 blocks**. An unknown continuation adds **+**. Near the centerline, the text becomes the remaining distance along the local path in the direction the player faces, such as **86 blocks ahead**. A plus on this text applies only when that chosen end reaches unknown terrain. Smart adds a relevant lead type near a marked endpoint.

Outside the tunnel, the tag anchors near its closest point. Inside, it moves a few blocks forward along the verified path so it can project in front of the camera. Tags reuse Chunk Finder's `RoundedRenderer.ReusablePanel`, `PanelBlur`, theme/font, two-line glass pill layout, projection and screen-overlap policy. Nearer tags win overlap ties, followed by longer tunnels. Scale clamps to 0.75–1.2, with size tinting, far-distance fading and eased appearance. The HUD and tags share one blur capture.

Visits and evidence survive chunk unloads. Switching modes retains visits and can immediately filter cached Smart evidence back to strict Normal sections before loaded chunks are refreshed. World/dimension/server changes and disconnects clear the session, including while disabled. History remains bounded to 2,048 chunks and 65,536 regular samples; oldest evidence is evicted at those limits. Rendering caps nearby spline samples at 8,192, markers at 48 and tags at 48, plus bounded retiring visuals.

## Timing and validation

Temporary profiling is opt-in through the JVM flag `-Dscrimmy.drillTrailTimings=true`; it adds no GUI setting. Every five seconds `TrailTimings` reports count, mean and maximum CPU wall time for snapshot slices, detection jobs, chaining jobs, tick geometry preparation, and render extraction plus submission. Profiling is off by default. A snapshot sample measures active work in a slice, not the full multi-tick latency of finishing a chunk.

The before/after fixture used 1,000 analyses of a 384-high chunk snapshot, 24 mixed sections, and a straight 3×3 passage per section. Bitmaps were prepared before analysis, matching the runtime snapshot pipeline. The figures include JVM warm-up and are local measurements, not FPS guarantees.

| Fixture stage (mean ms) | Before | Smart | Normal |
| --- | ---: | ---: | ---: |
| Detection | 2.444 | 0.984 | 0.533 |
| Chaining | 0.922 | 1.141 | 1.094 |
| Detection + chaining | 3.366 | 2.125 | 1.627 |

Chaining now builds connected-tunnel/tag metadata and is slightly more expensive. The detector and combined pipeline are faster; unchanged chunks no longer repeat that work. Raw fixture maxima (before / Smart / Normal) were 43.236 / 23.984 / 14.547 ms for detection and 45.453 / 28.340 / 47.748 ms for chaining, including JIT/GC/scheduling on workers.

The final Fabric client smoke run under Xvfb/Mesa software OpenGL recorded:

| Runtime stage | Samples | Mean ms | Maximum ms |
| --- | ---: | ---: | ---: |
| Snapshot slice/setup | 17,393 | 0.006 | 2.175 |
| Detection job | 193 | 1.131 | 59.080 |
| Chaining job | 111 | 0.701 | 7.681 |
| Tick geometry/tag preparation | 237 | 0.357 | 6.569 |
| World render extraction + submission | 675 | 0.486 | 73.822 |

These aggregate the five-second logs, including startup and mode changes. Worker timings do not block the tick. Render timing covers world geometry/projection/submission; the shared GUI blur/HUD draw is outside that counter. The software renderer showed first-use GPU/host scheduling spikes, so this run does **not** establish a no-spike guarantee on target hardware.

A temporary unshipped Fabric client gametest carved a cross-chunk tunnel, branch, room, solid plug and fixed hazard. It verified one Smart tunnel tag, Normal's exclusion of Smart markers/plugs, visits across a mode switch, removal of a plug through a real block update, visible **blocks ahead** projection, disable, disconnect and session clearing. Screenshots verified the existing glass pill appearance. The final build restores the original mod entrypoints and excludes the harness.

Java 25 `./gradlew clean build` passes all **99** regression tests. Regression coverage includes existing axes, borders, turns, shafts, stairs, crossings, closed loops, rooms, hazards and memory bounds, plus flat plugs across chunk borders, local-heading/two-layer plugs, rejected scattered/offset/thick blockages, two- and three-block zig-zags, repeated left/right turns, alternating vertical steps, path-length minimums, low-room rejection, spline samples staying in carved air, edge-result merging, mode filtering, settings migration and tooltips.

## Limits

Only loaded terrain can supply new evidence. Remembered unloaded sections cannot reveal later edits or unknown continuation. Geometry is a tunnel heuristic, not proof of a drill or a base. Very broken walls, other passage sizes, plugs thicker than two layers, and extremely dense networks may split or be capped. A tightly overlapping zig-zag can have several equivalent centerlines; length follows the retained traversable centerline. At a branch, the inside tag reports the local path ahead rather than predicting which branch the player will choose. Hazard warnings only describe observed data.

Software OpenGL validation exercises the actual dev client and glass rendering, but target-hardware latency and compatibility with third-party renderer/shader mods still need in-game checks. Startup shader/GPU work and host scheduling can exceed the cooperative scan budget; the budget is not a hard real-time guarantee.

## Files changed

- `src/client/java/com/scrimmy/client/module/basefinding/DrillTrail.java`: mode/settings, shared scheduling and session handling.
- `src/client/java/com/scrimmy/client/basefinding/DrillTrailScanner.java`: adaptive snapshots, priority, palette classification, bitmaps and neighbor strips.
- `src/client/java/com/scrimmy/client/mixin/ClientPacketListenerMixin.java`: full chunk replacement invalidation.
- `src/main/java/com/scrimmy/client/trail/TunnelAnalysis.java`: shared seeded detector, strict evidence, Smart joints/plugs/room checks.
- `src/main/java/com/scrimmy/client/trail/TunnelNetwork.java`: endpoint bridges, joint simplification and connected-tunnel metadata.
- `src/main/java/com/scrimmy/client/trail/TrailMath.java`, `TrailMemory.java`, `TrailTimings.java`: bounded smoothing/path lengths, partial evidence merges and opt-in timing.
- `src/client/java/com/scrimmy/client/render/DrillTrailRenderer.java`, `DrillTrailNametags.java`: cached curves, mode-gated extras and glass tags.
- `src/test/java/com/scrimmy/client/trail/TunnelAnalysisTest.java`, `TrailMemoryTest.java`, and `src/test/java/com/scrimmy/client/module/DrillTrailSettingsTest.java`: regression coverage.
- `README.md` and this document: controls, behavior, validation and limits.
