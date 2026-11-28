# BlockESP visibility and steady tracers

## Actual causes and active code path

The earlier `blockesp-persist` change is merged into `main` and is active:
`ModuleManager` constructs `BlockESPModule`; `initialize()` registers
`BlockEspRenderer.extract` at `END_EXTRACTION` and `draw` at `END_MAIN`.
`BlockEspCache` owns the per-chunk packed-position/block-type maps and sparse bit cursors.
There is no dependency on Minecraft's visible terrain sections, chunk meshes, raycasts,
block-entity rendering, or occlusion query results.

The previous fix retained detections but deliberately retained rendering culls:

- `extract()` called `FrustumIntersection.testAab()` and left off-screen blocks out of the draw list.
- The retained vertex shader clipped ribbons with both endpoints behind its near plane.
- Ordinary perspective projection also put behind-camera vertices outside the GPU clip volume.
- The depth-only path with Through walls off could suppress otherwise eligible highlights.

Consequently a camera turn could leave the cache unchanged while removing the entire
highlight from the screen. Keeping only the cache was insufficient for always-on-screen behavior.

Tracer motion had a separate cause. In Minecraft 1.21.11, `GameRenderer.renderLevel()` obtains
the projection, then multiplies in the `bobHurt()`/`bobView()` pose matrix. The old BlockESP
shader used that shared `Projection` uniform. Its start point was calculated using the
inverse view matrix, which did not cancel effects in the projection. The view matrix itself
is a clean camera rotation in this version. Player interpolation also used a separate tick
counter query rather than the camera's actual render partial tick.

## Current behavior

- `BlockEspDrawList.select()` receives no camera or visibility input. It selects valid cached
  blocks by player distance and a bounded nearest-block cap. A fixed scratch array doubles
  as a max heap and is sorted in place. `Arrays.sort(long[])` was removed from this frame path
  because its merge-run scratch array allocated 528 bytes per frame in the 2048-block fixture.
- No CPU frustum, view-angle, target-hit or visibility eligibility test remains in BlockESP.
  The shader never discards a block based on its direction. In-view boxes retain ordinary
  perspective; off-screen/behind-camera boxes use the same geometry and colors at a bounded
  viewport-edge anchor. This projection step is necessary to keep them literally on screen
  through a 180-degree turn. It changes placement, not selection or lifetime.
- Depth can only affect dimming. The default Through walls appearance still uses complementary
  visible/occluded passes with the existing hidden opacity. Turning that legacy control off
  removes dimming rather than hiding remembered blocks, as required by unconditional visibility.
- `BlockEspGameRendererMixin.scrimmy$blockEspProjection()` copies the world projection before
  bob/hurt effects and returns the original matrix unchanged. `BlockEspCamera.captureProjection()`
  stores that copy. BlockESP binds its own `BlockEspProjection` uniform; other modules keep
  Minecraft's normal matrices and the original shared shader.
- `BlockEspCamera.update()` uses the current frame's already-interpolated render-camera position
  and orientation. Crosshair starts 0.15 blocks along that camera's forward axis. Feet uses
  `interpolate()` on the player's previous/current positions with `Camera.getPartialTickTime()`.
  Both origins and all block centers share the same stable origin/camera-relative offset.
- Tracer origin has exactly two choices: Crosshair (default) and Feet. It only changes uniforms.
  Existing tracer toggle, colors, widths, styles, opacity, Range and drawn-block cap remain.
- Chunk knowledge is independent of the draw list. Chunk load/full replacement queues only
  that chunk; the existing client block-update hook updates the changed position directly.
  Real chunk unload, disable and world/dimension/session changes clear the corresponding data.
  Explicit block-selection edits retain their existing matching-cache rebuild behavior.
  Initial enable discovers already-loaded chunks with non-creating client lookups. Palette
  scans run in 64-position slices under the existing one-millisecond tick budget, never on frames.
- Retained geometry is rebuilt/uploaded only when its cache/visual membership or style/color
  revision changes. Rotating, walking, changing Tracer origin or updating the projection only
  changes frame uniforms/draw ranges. All matrices, arrays, uniform staging memory and the
  private projection buffer slice are reused. Minecraft still owns render submission and
  dynamic-uniform API wrappers. There are no new runtime network requests, synced registries,
  common-source files, or changes to other modules' behavior.

## 0 degrees / 180 degrees / return walkthrough

1. `BlockESPModule.onTick()` calls `BlockEspCache.tick()`. With the same player position,
   loaded chunks and block states, detection knowledge is unchanged. `offer()`/`publish()`
   keep the same distance-selected visuals and `renderRevision()`. `load()`, `changed()` and
   `unload()` receive no events merely because the player turns; `scan()` is not requeued.
2. `BlockEspRenderer.extract()` calls `BlockEspDrawList.select()`: the same block IDs/count
   and distance ordering are returned for 0, 180 and 0 degrees. `rebuild()` is not called.
3. `BlockEspCamera.update()` updates the camera orientation/model-view and steady tracer origin.
   No stored block or geometry changes. The clean projection comes from
   `scrimmy$blockEspProjection()` -> `captureProjection()` before bob/hurt modification.
4. Shader `main()` calls `projectBlock()`. At 0 degrees the world box is in view; at 180 it
   remains visible at the edge; returning restores its normal placement. No direction can
   remove its draw command or send the whole highlight behind the clip plane.
5. `BlockEspRenderer.draw()` / `drawRanges()` submit the same retained vertex buffer and
   eligible block count at all three orientations. Camera/projection uniforms and projected
   positions change as expected; cache entries, selection and geometry do not.

## Verification

- Java 25 clean build and JUnit suite (195 tests): cache/block/chunk lifecycle, exact nearest cap, rotation-independent
  draw selection, unchanged geometry revisions, both Tracer origin config values, smooth
  Feet interpolation, and Crosshair alignment over a full yaw/pitch/movement sweep.
- A `ThreadMXBean` regression checks 1000 warmed frame-helper iterations at 2048 blocks:
  zero bytes allocated by draw selection plus camera transforms after replacing the sort.
- Surfaceless Mesa OpenGL: both shaders compile/link; the old shader produces zero pixels
  for a behind-camera block; the new shader produces highlight pixels at every degree of a
  360-degree sweep, including near-vertical pitch cases. In-view outline/fill geometry matches the old shader pixel-for-pixel.
  Fade, Crosshair/Feet tracers, unchanged vertex data and GL error checks pass.
- An unshipped client initializer created a flat integrated-server world with three chests.
  The real Fabric extraction/draw hooks retained all three highlights and the same GPU buffer
  across 0/180/0 turns. Walking with bob enabled plus hurt shake kept Crosshair on the camera
  axis; Feet, unconditional drawing with Through walls off, actual server block removal and
  clearing on disable passed. Screenshots also verified in-view and behind-camera highlights. The helper and
  graphics scripts live under `/tmp`, are excluded from the final clean artifact, and never
  require the user to run diagnostics.

Build command (repository build files unchanged):

```sh
JAVA_HOME=/tmp/blockesp-jdk GRADLE_USER_HOME=/workspace/.gradle ./gradlew clean test build \
  -Ploom_version=1.18.4 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
```

Loom's configured `1.18-SNAPSHOT` metadata was unavailable, so validation uses the temporary
property override and environment certificate store. The smoke run used software Mesa/Xorg;
physical GPU frame times, third-party renderers and an actual LAN join remain unmeasured.
