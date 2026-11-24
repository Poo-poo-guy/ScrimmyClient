# ActivityLog

ActivityLog is a client-only Basefinding module for Fabric / Minecraft 1.21.11.
Enable it in ClickGUI to capture live positional evidence. Press **J** or use
**Open activity log** to browse saved history, including while capture is disabled.
The normal module keybind still toggles capture; the separate panel key is configurable
with **Set panel key** in the Panel settings (press a key; Escape clears it).

## Capture and display

- Positional sound packets and mapped level-event sounds use SoundFinder's packet
  decoding, sound-to-block confirmation and level-event mapping. SoundFinder's
  ignore list and entity-sound toggle continue to affect only SoundFinder;
  ActivityLog captures all positional sounds when its Sounds toggle is on.
  Missing chunks produce **unconfirmed / unloaded** evidence without loading chunks.
- Player spawn, relative movement, position sync, teleport, equipment and metadata
  packets record UUID, profile name, server position, dimension and equipment.
  Missing player-info/equipment is explicitly **unknown**, never fetched.
  Relative movement uses the packet codec, rather than the interpolated entity position.
- Single-block and section-delta updates are captured before vanilla applies or
  rejects them. Only unloaded or known below-surface columns qualify. Nearby block
  changes merge and retain up to eight distinct block IDs with counts.
- Level events, block events and explosion packets retain their positions and details.
- Optional items, mobs, item frames, armor stands, minecarts and boats produce chunk
  cluster centers and entity counts when at least two tracked entities are present.
  The worker groups immutable packet snapshots; individual entities are not saved
  as separate log entries. Static clusters are not repeatedly logged without packets.
- No chat, chunk requests, synthetic game content, telemetry or network calls.

The surface test reads a loaded column's `WORLD_SURFACE` heightmap. Below the
highest surface block is underground. Missing heightmaps/chunks are unknown;
**Record underground only** excludes unknown positions. Nothing reconstructs
underground facts the server did not send while connected. A fresh join can add
new live sightings, alongside the saved entries from earlier sessions.

Entries merge by category and identity, dimension, login session, radius and last-hit
window. Players with different UUIDs stay separate. Sound IDs stay separate so their
details are preserved. A spatial index bounds candidate searches. Repeat scoring,
base sound weights, sustained bonuses and severity thresholds use SoundFinder's
shared scoring utility and current settings. Different categories in the same
chunk add its cluster weight, and underground evidence adds its unusual weight.
JSON `score` stores the captured base/repeat score; the panel and markers include
the derived underground and chunk bonuses. Historical scores do not decay merely
because the client reconnects.

The panel has category, minimum severity, dimension, underground and player-name
filters, plus recency/score sorting. Drag its title or resize from its lower-right
corner. Position and size persist through the usual client settings. Rows show
category, severity, details, hit count, coordinates, dimension, distance, age and
session. Selecting a same-dimension row creates one **local vanilla locator-bar
waypoint**, replacing the previous ActivityLog waypoint. Its visibility follows
vanilla locator-bar settings. Selecting another dimension copies its coordinates.
Deleting that entry, clearing history, disabling capture or changing worlds removes
only ActivityLog's waypoint. Server waypoints are preserved.

Markers are faint through-wall boxes. Off, Recent only (configurable minutes) and
All within a distance modes are available, with severity and distance filters.
At most 512 nearest markers are prepared, every five ticks; no history filtering,
merging or disk work runs in render hooks. Tags reuse SoundFinderRenderer,
SoundTagSelection, SoundTagAnimation and the existing shared easing implementation,
including camera-cone selection, hysteresis, linger, crossfades and glass/blur.
SoundFinder's look angle, animation settings and tag mode also apply to ActivityLog.
Severity colors and minimum severity belong to ActivityLog independently.

## Storage and lifecycle

Files live at `config/scrimmy/activitylog/<sha256-server-key>.json`, one JSON array
per normalized multiplayer server address, or per local save path. Dimensions share
a file. Server addresses are hashed for safe filenames; files are **not encrypted**.
Coordinates, UUIDs, names and equipment remain local to that client.

The single background history worker loads, merges, scores, prunes and serializes.
It writes every five seconds when dirty, on disconnect and at shutdown. Replacements
use a sibling temporary file and atomic move where supported. Joining queues the
load before live captures so events arriving during loading are retained. Switching
server flushes the old file before opening the new one; login sessions and dimensions
prevent accidental merges. Bad files are preserved and an error appears in the panel.

Defaults: 5,000 entries, 16 MiB file cap, no age expiry. Pruning removes the lowest
score first, oldest last-seen first on a tie; 0 expiry days means never. File-size
pruning uses UTF-8 bytes and produces valid JSON. Input files over 64 MiB are refused.
Packet buffers and the worker queue are bounded (16,384 client-buffer events,
32,768 pending worker events, 16,384 optional cluster members, 4,096 player packet
positions); a saturated worker queue drops new events and reports it in the panel.

## Validation

Automated coverage checks merge boundaries, sessions/dimensions/server separation,
join/load ordering, relog persistence, repeat saturation, underground/type bonuses,
entry and byte caps, expiry, deletion/clearing, invalid-file preservation, cluster
centers/counts, and local waypoint lifecycle without deleting server waypoints.
Packet-hook tests inspect Minecraft 1.21.11 bytecode for the exact client-thread
handoff targets and verify both new mixins are client-only. Existing SoundFinder
tracking, scoring, selection and animation tests remain part of the full build.

Manual two-client acceptance checklist (requires Minecraft instances and an alt):

1. Enable ActivityLog. Place an alt out of sight, below a known surface. Move it,
   change held item/armor, open containers and trigger doors/pistons/explosions.
   Check coordinates, name/UUID, equipment, underground flag, counts and severities.
2. Send sound/block-delta evidence for an unloaded chunk. Confirm it is recorded
   without chunk loading or block confirmation; verify other dimensions stay separate.
3. Enable optional clusters, spawn multiple items/mobs/frames/stands/carts/boats,
   then remove them. Check one cluster center/count rather than individual rows.
4. Relog and `/rtp`; check old entries and sessions remain. Inspect the JSON after
   five seconds and disconnect. Lower the caps/expiry to check pruning and valid JSON.
5. Exercise drag/resize, search, filters/sort, delete/clear, all marker modes,
   severity colors and render-distance culling. Look between nearby tags to check
   hysteresis/crossfades. Compare SoundFinder alone before and after this change.
6. Select a current-dimension row and check the local locator-bar waypoint. Select
   an entry in another dimension and paste the copied coordinates. Confirm existing
   server waypoints survive replacement, deletion and dimension changes.
7. Open a LAN world with the new jar and join from a second instance using the older
   post-notification-registry-fix jar. Confirm there is no unknown-registry mismatch.

The headless build environment cannot perform the alt, interactive rendering,
clipboard or two-instance LAN steps; these must be performed in game.

## Files changed

- `src/client/java/com/scrimmy/client/module/basefinding/ActivityLog.java`: declaration,
  grouped settings, capture/lifecycle/tick integration and historical marker provider.
- `src/client/java/com/scrimmy/client/activity/{ActivityCapture,ActivityCluster,ActivityEntry,ActivityHistory,ActivityWaypoints}.java`:
  packet positions/equipment, immutable snapshots, worker-owned history and local waypoints.
- `src/client/java/com/scrimmy/client/gui/{ActivityLogScreen,ActivityLogKeyScreen}.java`: draggable/resizable glass log and panel-key capture.
- `src/client/java/com/scrimmy/client/mixin/{ActivityPacketListenerMixin,MoveEntityPacketAccessor}.java`
  and `src/client/resources/scrimmyclient.mixins.json`: client packet observation.
- `src/client/java/com/scrimmy/client/sound/{SoundCapture,SoundTracker}.java`,
  `src/client/java/com/scrimmy/client/render/SoundFinderRenderer.java`, and
  `src/client/java/com/scrimmy/client/module/basefinding/SoundFinder.java`: shared
  capture/confirmation, scoring and renderer provider; original SoundFinder behavior retained.
- `src/client/java/com/scrimmy/client/module/ModuleManager.java`: Basefinding module list.
- `src/test/java/com/scrimmy/client/activity/`, `ActivityLogSettingsTest.java`, and
  `ModuleSettingsTest.java`: regression coverage and the documented grouped-setting allowance.
- `ACTIVITYLOG.md` and `REGISTRY-AUDIT.md`: usage, limits, audit and manual acceptance checklist.
