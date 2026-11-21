# Module toggle notifications

Settings live in **Client → ScrimmyClient+ → Notifications**, a collapsible group
inside the existing appearance row. The separate Notifications module is removed.
The Notifications switch is the master control, enabled by default.

Controls:

- Style: Card (the existing two-line layout) or Pill (one-line capsule, ON/OFF at right).
- Position: Top Right, Bottom Right, Top Left, or Bottom Left.
- Duration: 1–6 seconds; Source: Keybind only (default) or All toggles.
- Opacity: background opacity in glass mode; solid mode stays opaque.
- Corner Radius: Auto preserves the global Card radius and makes Pill fully rounded;
  an explicit 0–26 value overrides both, clamped to half their height.
- Accent Tint: accents enabled state text and subtly tints enabled glass surfaces.
- Sound: enabled by default; Volume: 0–1 (default .25); Pitch Variation: enabled by default.

Auto is saved as `-1` and shown as “Auto” in the GUI. Tint, opacity, radius and width
changes use the existing animation utilities; global animations off snaps transitions.
Both styles share global glass/solid appearance, blur, active font (including Xuong),
accent, glow, shadow, motion style and speed.

## Configuration migration

Notification controls are stored with the other ScrimmyClient+ settings under
`profiles.default.theme`, using the `notifications` key prefix. On load,
`ModuleSettings.appearance(theme, modules)` migrates the retired module's enabled
state, Position, Duration and Source. Existing canonical values take precedence
independently for each setting. Previous global opacity initializes notification
opacity when a dedicated value has not been saved. Malformed values retain safe
defaults without discarding other valid controls.

Saving writes only the new settings and currently registered modules, so the old
`modules.Notifications` entry disappears. Notification defaults and collapsible
section state use the existing settings codec and reset flow.

## Preserved hooks and queue

The central toggle hook remains `Module.enabled(boolean, ToggleSource)` after the
actual state/lifecycle change. ModuleManager still forwards every registered module
to the existing listener, and the keybind and ClickGUI paths retain their source
annotations. The listener starts after config restore and module initialization.
The master switch clears visible/pending notifications and blocks new ones while
off; it is an ordinary ScrimmyClient+ control, not another module toggle.

The verified 1.21.11 hooks are unchanged:

- `Gui.render(GuiGraphics, DeltaTracker)` TAIL when no screen is open.
- `Screen.renderWithTooltipAndSubtitles(GuiGraphics, int, int, float)` TAIL,
  including ClickGUI and deferred screen content.
- Existing `GameRenderer.render` HEAD frame initialization supplies the frame id.

Both hooks use one renderer and the existing once-per-frame guard. No vanilla toast
API is used. The queue still retains at most four visible and four pending requests;
overflow waits for the oldest visible toast to finish fading. Same-module toggles
update in place, reverse unfinished exits and extend the timer.

Each toast captures its style when admitted. Changing Style affects new toasts
immediately; visible cards/pills finish in their original style, including duplicate
refreshes. Mixed stacks use each toast's actual height plus the existing gap.

## Shared rendering and sound

`CardToastRenderer` and `PillToastRenderer` provide separate content layouts.
`GlassToastRenderer` contains common glass drawing, placement, animated sizing,
customization and the reversible state crossfade. The unchanged queue owns entrance,
exit, state and stacking animation; its offset calculation now supports both heights.

Pill state labels slide vertically and fade against one another using the retained
state spring; their color eases between muted and enabled colors. Duplicate updates
never replace a visible pill. Name/font width changes retarget the existing width
spring without restarting the entrance.

`RoundedRenderer.ReusablePanel`, shared `PanelBlur`, and `ClientFonts.RetainedLabel`
remain in use. Glyph runs prepare on ticks/content or atlas changes, outside drawing.
Style renderer instances, geometry, labels, texture bindings, tint adapters and
matrices are retained; no notification-owned render objects, strings or lists are
created per frame. Minecraft owns its normal GUI/GPU submission memory.

`NotificationSound.onShown(boolean)` now runs for newly admitted cards/pills and
actual state flips inside a visible toast. Identical-state/name-only updates,
waiting duplicates and discarded overflow do not request audio. Source filtering
and the Notifications master switch still happen before toast admission.

`NotificationAudio.initialize`, called once from `ScrimmyClient.onInitializeClient`,
installs the existing sound hook and client-tick/reload callbacks. The client-only
`ClientNotificationSounds` class creates unregistered `SoundEvent` handles for
`scrimmyclient:notification_on` and `scrimmyclient:notification_off` directly with
`SoundEvent.createVariableRangeEvent`. Neither identifier is inserted into a
Minecraft registry. Both unchanged definitions in `assets/scrimmyclient/sounds.json`
reference `scrimmyclient:aula_click1` with preloading enabled. ON uses pitch 1.0,
OFF .82, with optional ±.035 variation. The recording is shared, not duplicated.

The mapped 1.21.11 `SimpleSoundInstance.forUI(event, pitch, volume)` creates a
`SoundSource.UI`, listener-relative sound with no attenuation. The engine applies
master/UI gain; the notification volume is passed without multiplying it again.
Zero master/UI volume, zero notification volume, Sound off, and Notifications off
all suppress playback.

The hook only stores primitive request state. Playback is flushed on client ticks,
including requests admitted by the renderer between ticks. A START_CLIENT_TICK
counter limits playback to one sound per tick even if both flush points run.
Requests in a burst coalesce to the latest state; muted/throttled requests are
dropped rather than queued for later. Sound-instance allocation occurs only on
an audible client-tick event, never in the notification render path. Resource
availability is checked during reload, and Minecraft preloads/caches the recording.
There is no per-toggle file loading.

### Bundled recording

The single supplied file is `assets/scrimmyclient/sounds/aula_click1.ogg`:
mono Vorbis at 48 kHz, about 79 ms long. Both registered events reference that
recording; OFF is distinguished by its lower pitch. Resource availability is
rechecked on reload, so a missing or unavailable recording stays silent safely.

## Validation

The Gradle build/remapping and all 135 JUnit tests passed. Tests cover migration,
canonical precedence, corrupt legacy values, settings round trips, grouping, Auto
radius defaults, mixed heights, retained styles, in-place name/state changes, and
all previous queue/sound/toggle behavior.

The Fabric runtime smoke test passed for Card/Pill lifetime switching, mixed stacks,
Pill ON/OFF crossfades, smoothly changing width, appearance customization, Xuong,
source filtering, the master switch, the existing frame guard and sound extension
point. Screenshots confirmed rendering above ClickGUI. A saved config was checked
for canonical notification settings and absence of the old module entry. Temporary
smoke tooling is excluded from the mod.


Audio-specific validation passed for tick coalescing, bounded pitch variation,
settings persistence, visible state changes, unchanged-state deduplication, pending
updates and discarded overflow. Resource tests verify both preloaded definitions
resolve to the same packaged Vorbis recording. The Fabric runtime smoke test uses
the actual bundled Aula file and verifies resource definitions, resolved recording and
active audio channel, UI-relative/no-attenuation instances, lower OFF pitch,
same-tick limiting, source/master/Sound filtering, zero notification/UI/master
volumes and pitch variation. Temporary smoke tooling is excluded from the mod.


## LAN registry compatibility

The two previous `Registry.register(BuiltInRegistries.SOUND_EVENT, ...)` calls were
removed. Registering them from a client initializer still mutated the built-in
registry shared with the integrated LAN server. Unregistered sound handles resolve
through the client SoundManager by identifier and require no synchronized raw id.

All notification code, the particle pool/math helpers, and their Theme owner now
live under `src/client/java`; the assets remain at their original resource paths.
No notification/particle/registry code remains in the common Java source set.
The client-only mod descriptor and client entrypoint are unchanged, and no common
initializer is declared. HitParticles already draws client geometry without any
custom ParticleType registration, so its behavior is unchanged.

See REGISTRY-AUDIT.md for the full before/after inventory. The final synchronized
registry additions list is empty. An isolated local LAN test passed with an old
Scrimmy JAR guest (which still had both registered sound entries) and with a guest
without a Scrimmy JAR. The fixed host's notification audio channel worked before
and after both connections. This test used offline development profiles and a
test-only authentication override; the two-authenticated-account matrix remains a
manual check. No production networking or authentication code was changed.
