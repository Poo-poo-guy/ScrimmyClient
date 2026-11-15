# LogoutGhosts

LogoutGhosts is off by default under **Render**, alongside the other world markers. Enable it in a world, then toggle **Test Ghost** to put a preview six blocks along your look direction. Test Ghost resets itself immediately.

## Tracking

Loaded remote players share reusable snapshots. Losing both the entity and its tab entry creates a **LOGGED OUT** marker. Entity removal can precede tab removal, so a snapshot stays pending for up to 40 ticks. Older unloaded snapshots cannot produce a fresh logout marker later.

**Out Of Range Ghosts** also creates **LAST SEEN** markers when the entity unloads while tab still lists the player. That setting is off by default. A continuously present tab entry keeps this marker alive; a loaded return removes it. A missing tab entry that later returns also removes a marker. A range marker can become a logout marker if tab removal follows promptly.

A player remaining loaded without tab information for 20 ticks is classified as vanished. **Notify Vanish** can report that once per disappearance, independently of **Notify Chat**. Vanish detection does not create a ghost. Dead snapshots cannot create ghosts.

World changes, dimension changes, disconnects, respawns, and disabling the module clear the session. This follows the requested clarification to clear on dimension changes. Each marker still carries its dimension, and rendering rejects a different dimension. Ghosts are temporary and are not saved to disk; settings use Scrimmy's existing config codec.

## Rendering and tuning

- **Duration (s)** and **Max Ghosts** control retention. Excess markers are removed oldest first. Fade-out occupies the shorter of the last 10% of the lifetime and the last 10 seconds.
- **Head size** and **Label size** adjust the clamped, distance-dependent HUD sizes. For different limits, edit `HEAD_MIN_SIZE`, `HEAD_MAX_SIZE`, `LABEL_MIN_SCALE`, and `LABEL_MAX_SCALE` at the top of `LogoutGhostRenderer`.
- **Ghost Color** controls body, beam, and label tint and opacity. `GhostMath.OLD_COLOR` controls the aged gray. Fade Distance gently dims distant markers; Max Draw Distance hides them completely beyond its limit.
- Body glow, pulse, bob, beam height, and head spin are named constants at the top of `LogoutGhostRenderer`.
- All visual parts can be toggled independently. Show Coordinates adds a fourth label line. Chat creation messages include coordinates and dimension regardless of that label setting.

Body and beam use the same extraction/END_MAIN hooks, AA ribbons, and shader as BlockESP. Their pass has no depth attachment, depth test, or depth writes. The label uses a reusable version of Scrimmy's rounded translucent panel style.

Heads project a camera-relative world point through Fabric's view/culling projection, reject negative/near-zero perspective `w` and off-screen points, then use the existing AntiVanish picture-in-picture renderer. Each marker reuses a fixed 64-by-64 GUI preview and changes its GUI pose to move/scale it. This avoids resizing a texture whenever the camera moves. The shared six-face skin/hat mesh, perspective, nearest-neighbor sampling, and isolated preview depth are retained. Marker opacity is a separate state; AntiVanish keeps its original opaque default.

The module reuses geometry buffers, arrays, matrices, panels, and head states. Label strings and widths refresh on ticks rather than frames. Vanilla GUI text, render-pass, and PIP internals still perform their own bookkeeping allocations; these APIs do not provide a completely allocation-free render path. First use and screen-size changes also require GPU/viewport setup. No new dependencies or access wideners were added.

## Validation and limits

`./gradlew build` passes with Java 25. Tests cover departure/return transitions, vanish grace, delayed tab removal, death/session suppression, capacity/expiry, snapshot reuse, perspective rejection, fade timing, and compatibility of the shared head state. The head shaders also compile and link in headless OpenGL.

Live multiplayer packet ordering, final visual placement (including view bobbing/FOV effects on the culling projection), and sound need an in-game play test. Client-visible entity/tab changes are heuristics: a server transfer, hidden player, or render-distance change may resemble departure. Players never loaded by the client cannot leave markers.

## File list

New:

- `LOGOUTGHOSTS.md`
- `src/client/java/com/scrimmy/client/ghost/LogoutGhostTracker.java`
- `src/client/java/com/scrimmy/client/module/visual/LogoutGhosts.java`
- `src/client/java/com/scrimmy/client/render/LogoutGhostRenderer.java`
- `src/main/java/com/scrimmy/client/ghost/GhostMath.java`
- `src/test/java/com/scrimmy/client/ghost/LogoutGhostTrackerTest.java`
- `src/test/java/com/scrimmy/client/ghost/GhostMathTest.java`

Changed:

- `src/client/java/com/scrimmy/client/module/ModuleManager.java` — register the new module.
- `src/client/java/com/scrimmy/client/render/AntiVanishHeadRenderer.java` — reusable positioned/fading marker states; existing warning defaults retained.
- `src/client/java/com/scrimmy/client/render/RoundedRenderer.java` — add reusable panels without changing existing panel methods.
- `src/client/resources/assets/scrimmyclient/shaders/core/anti_vanish_head.vsh` — apply the supplied color/opacity uniform, white/opaque for AntiVanish.
- `src/test/java/com/scrimmy/client/render/AntiVanishEffectTest.java` — shared renderer compatibility checks.

No new shader files. The existing `block_esp.vsh`/`block_esp.fsh` pair is reused unchanged. No access wideners.
