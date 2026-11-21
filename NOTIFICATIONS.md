# Module toggle notifications

**Client → Notifications** is enabled by default. Its three controls are Position
(Top/Bottom, Left/Right), Duration (1–6 seconds), and Source (Keybind only by default,
or All toggles). Glass/solid appearance, blur, tint, radius, accent, glow, shadow,
font, animation style, speed, and animation enablement follow the global theme.
An explicit saved disabled state is preserved; older configs without the new module
use its enabled default. Other modules retain their previous disabled defaults.

## Toggle hook

`Module.enabled(boolean, ToggleSource)` is the sole event emission point, after
lifecycle handling and the actual state change. No-op writes emit nothing. If a
module vetoes activation by disabling itself during `onEnable`, it cannot emit an
incorrect Enabled notification afterward.

`ModuleManager.register` wires every registered module, including future modules,
to one listener. The existing keybind loop calls `toggle(KEYBIND)`; ClickGUI module
rows call `toggle(CLICK_GUI)`. Existing setter/toggle calls remain compatible and
are classified PROGRAMMATIC. All toggles includes all three sources. The listener
is attached after config restore and module initialization, avoiding startup spam.

Disabling Notifications clears existing toasts and, when the selected source allows
it, shows its own final Disabled confirmation. Other modules remain silent while
the notification module is off. No module behavior or input handling is delayed.

## Verified 1.21.11 render hooks

Targets were checked in Loom's generated official Mojang sources and the compiled
1.21.11 classes:

- `Gui.render(GuiGraphics, DeltaTracker)` TAIL: draws on the HUD when no screen is open.
- `Screen.renderWithTooltipAndSubtitles(GuiGraphics, int, int, float)` TAIL: this final
  wrapper covers screen subclasses, including ClickGUI, after screen content and
  deferred tooltips have been submitted.
- Existing `GameRenderer.render` HEAD frame initialization advances the shared
  notification frame id. Both hooks call the same `NotificationRenderer.render`;
  it permits only one submission per frame and waits during resource-load overlays.

The renderer creates a separate GUI stratum and uses no vanilla toast APIs.
`ToastQueue` retains four visible cards and at most four pending requests. A fifth
card waits for the oldest visible card to finish fading. Repeated toggles update
an existing card in place, reverse an unfinished exit, and extend its hold.
New cards slide in using the existing OUT_BACK easing; exits use OUT_CUBIC, and
stacking/state changes reuse the analytic spring utility. Animation settings are
shared with the ClickGUI.

## Retained rendering and sound

Panels use `RoundedRenderer.ReusablePanel` and the existing shared `PanelBlur`.
`ClientFonts.RetainedLabel` prepares all font-family glyph runs outside rendering,
then uses the existing family blend weights, including Xuong. Retained glyph
states, texture bindings, vertex tint adapters, and matrices avoid rebuilding text
layouts or notification-owned draw objects each frame. Layouts rebuild on content
or font-atlas revisions during client ticks. Viewport bounds rebuild only on resize.
Minecraft still owns its ordinary GUI queue and GPU submission memory.

`NotificationSound.onShown(boolean enabled)` is an empty extension point. Its
default implementation does nothing; no notification audio is implemented. It is
called when a new card is admitted, including a queued card, and receives that
card's current enabled flag. Duplicate state updates do not replay it. A failing
future sound provider cannot interrupt module state changes.

## Validation

The full Gradle build, remapping, and all 123 JUnit tests passed. New tests cover
central event sources, no-op setters, lifecycle veto, defaults, deduplication,
expiry, exit reversal, bounded overflow, smooth stacking, animation disablement,
and the sound extension point.

An actual Fabric client smoke test under a virtual display passed for keybind,
ClickGUI, and programmatic source filtering; duplicate updates; the four-card
limit; once-per-frame submission; all positions; glass/solid themes; Xuong;
animations off; and the final self-disable confirmation. Screenshots confirmed
that the custom toasts render above the ClickGUI. The HUD target also transformed
successfully; its no-screen branch was verified in the mapped source. Temporary
smoke tooling is excluded from the distributed mod.
