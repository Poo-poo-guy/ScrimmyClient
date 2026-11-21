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

`NotificationSound.onShown(boolean)` is unchanged and silent by default. It runs
when a new card or pill is admitted, not for duplicate updates. No audio is added.

## Validation

The Gradle build/remapping and all 129 JUnit tests passed. Tests cover migration,
canonical precedence, corrupt legacy values, settings round trips, grouping, Auto
radius defaults, mixed heights, retained styles, in-place name/state changes, and
all previous queue/sound/toggle behavior.

The Fabric runtime smoke test passed for Card/Pill lifetime switching, mixed stacks,
Pill ON/OFF crossfades, smoothly changing width, appearance customization, Xuong,
source filtering, the master switch, the existing frame guard and sound extension
point. Screenshots confirmed rendering above ClickGUI. A saved config was checked
for canonical notification settings and absence of the old module entry. Temporary
smoke tooling is excluded from the mod.
