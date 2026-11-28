# BlockESP persistence

## Investigation

`BlockESPModule` delegates detection to `BlockEspCache` and drawing to `BlockEspRenderer`.
The original detector did **not** test camera direction or the view frustum. The renderer's
frustum test suppressed off-screen drawing. The actual cache eviction defect was in
`BlockEspCache.recenter`: it discarded entire chunks beyond the module's Range even when
those chunks were still loaded by the client. `load` also rejected chunks outside that
range. Separately, the renderer rebuilt camera-relative vertices and uploaded them every
frame, and owned fading visual removal.

A pure camera turn cannot be attributed to the old cache eviction from this code alone.
The original look-only symptom was not reproduced in a running Minecraft session. Normal
off-screen culling remains intentional; returning to a remembered block does not need a
new scan.

## Behavior

- Each loaded chunk owns a fastutil packed-long-position-to-block map. Section bitsets
  provide a resumable sparse cursor; colors use the stored block type rather than a new
  world lookup during draw selection.
- Chunk load/full chunk replacement queues that chunk only. Initial enable discovers
  already-loaded chunks without requesting any from the server. Scanning uses 64-position
  slices and shares the existing one-millisecond tick budget with discovery/selection.
- The existing client `LevelChunkMixin` block-update hook updates individual entries.
  Chunk unload removes that chunk; disable, disconnect, world/dimension change and shutdown
  clear the session. Changing the block selection explicitly rebuilds matching knowledge.
- Moving, looking away, draw-distance changes, draw caps and empty render selections never
  evict chunk knowledge. Client chunk load/unload events remain the authority for lifetime.
- Range is now only a maximum draw distance (same key/default: 96 blocks). Max drawn blocks
  defaults to the previous cap of 512 and allows 64–2048. Fading visuals have a bounded pool.
- Visual membership/fade transitions and style/color changes dirty retained geometry.
  Stable frames reuse the vertex buffer and fixed scratch arrays. Camera transforms,
  distance fade, pulse, line clipping and tracer anchoring run through uniforms/shaders;
  stable frames create no lists, streams, matrices or vertex data. Minecraft still owns its render
  submission/uniform wrapper allocations. Adjacent draw ranges are batched.
- The new vertex shader belongs only to BlockESP. The original shader and shared renderers,
  scanners and block-update mixin are unchanged. There are no new common-source classes,
  synced registry entries, packets or runtime network requests.

## Verification

Java 25 clean build and all JUnit tests passed using:

```sh
JAVA_HOME=/tmp/blockesp-jdk GRADLE_USER_HOME=/workspace/.gradle ./gradlew clean test build \
  -Ploom_version=1.18.4 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
```

The checkout's `1.18-SNAPSHOT` Loom metadata returned 404; the command used a temporary
property override and the environment's certificate store. Build files were not changed.

Cache regressions cover out-of-range retention, empty draw selections, stored block-type
replacement/removal, per-chunk invalidation/repopulation, clear, capped nearest selection,
visual recycling and stable geometry revisions. A temporary surfaceless Mesa OpenGL harness
compiled/linked both shaders, compared outline/fill geometry pixel-for-pixel, turned away
and back with identical vertex-buffer contents/output, and checked fade/tracer rendering
and GL errors. That harness was outside the repository and is not packaged.

Live Minecraft/LAN joining, third-party renderers and target-hardware FPS/allocation profiling
remain unverified. Manual checks should include initial enable with loaded chunks, turning
while stationary, leaving/re-entering draw range while chunks remain loaded, mining/placing
matching blocks, actual chunk unload/reload, dimension changes and dense custom selections.
