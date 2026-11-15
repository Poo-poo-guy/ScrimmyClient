# Freecam

Enable **Freecam** in the Render category. It is off by default. Use the module's existing **Key** setting to assign a toggle hotkey.

- Movement uses your configured forward/back/strafe keys and follows the camera's look direction.
- Jump moves straight up; Sneak moves straight down.
- Sprint boosts flight speed (default 3x).
- Scroll up increases speed; scroll down decreases it. The action bar shows the new speed. Fractional wheel/trackpad input accumulates, and scrolling does not switch hotbar slots while flying.
- Opening a screen, losing focus, or pausing stops camera movement. Menu scrolling remains normal.
- Disable the module to return to the player's normal view. Death, respawn, dimension/world change, disconnect, or switching the camera to another entity also ends freecam.

The **Speed (blocks/s)** and **Sprint speed multiplier** settings persist through Scrimmy's normal config. Base speed ranges from 0.1 to 128 blocks/s; its initial value is 8 blocks/s.

The free camera has its own coordinates and angles. Camera setup applies them after vanilla third-person collision handling, and terrain culling uses vanilla's spectator handling inside walls. The real player's position, rotation, flight abilities, and collision flags are not changed by the camera. Player movement input and auto-jump are suppressed while detached; mouse sensitivity/inversion still come from vanilla. Normal server physics, damage, and other modules continue to apply to the player's body.

While detached, manual attacks, mining, item use, picking, dropping, and offhand swaps are suppressed. The first-person hands, body-based view/hurt bobbing, and stale crosshair target are hidden. GUI screens and module hotkeys remain available. Turning freecam off restores the original player input and vanilla camera behavior, without teleporting the player to the camera. No entity is spawned and the camera entity is not replaced. Exploration is limited to chunks the client has loaded.

Implementation: `Freecam` owns the session/input lifecycle, `FreecamMotion` owns movement/interpolation/scroll calculations, and five narrowly scoped mixins connect camera setup, mouse input, hand rendering/picking/bobbing, manual interactions, and terrain culling. All hooks check for an active freecam before changing behavior. No new dependencies, shaders, or access wideners.

Validation: Java 25 `./gradlew build`; movement/diagonal/pitch/interpolation/scroll tests; player input/auto-jump suppression; and actual Minecraft 1.21.11 class-file checks for injection signatures and call sites. These bytecode checks do not replace an in-game test of mixin application, wall traversal, rendering, or multiplayer behavior.
