# In-game checks

- Start with a fresh config, or reset the GUI. Check all five panels at Minecraft GUI scales 1–4 and client scales 0.75/1/1.5, windowed/fullscreen, and after resize. Confirm no initial overlap, correct hover/click/drag coordinates, smooth collapse and scroll, front-on-click ordering, and saved custom positions/open states. Extremely short viewports prioritize keeping all five headers reachable; drag a panel to give it the full available height.
- In Visual, enable HitParticles and hit players, hostile mobs, and passive mobs. Verify rejected attacks produce no particles. Try target filters, crit-only, kill-only, and attributed projectile damage. Test player/non-player kill attribution in multiplayer.
- Try every shape, each star point count and inner ratio, all five motion modes, billboard/fixed orientation, negative gravity, outlines, alpha colors, gradient stop editing, palette colors, and per-shape overrides. Check smooth motion at low and high FPS.
- Try all six built-in presets; edit a control and confirm Custom. Save to multiple user slots, restart, then load them. Check shape pools cannot become empty and malformed config settings keep valid neighbors.
- Toggle Through walls behind solid geometry; compare normal/additive blending, halo sizes, trails, twinkle, scale/fade easing, size/lifetime ranges, optional sound, and distance culling. Stress 128 particles per hit and a 4,096 cap with trails; monitor frame time and allocation/GC on your GPU.
- In ScrimmyClient+, change accent, blur, opacity, radius, GUI scale, and animation speed while Visual is open. Confirm text remains aligned, drag tracks remain stable, blur stays inside rounded panels, and the scene is sharp outside them.
- Toggle every module, bind multiple modules to the same key, close/reopen, and restart. Verify enabled states, settings, layouts, and presets restore. Test AutoTotem from hotbar/main inventory, with an existing offhand totem, a container open, its health threshold, and click delay.

# Changed-file map

- Entry point and routing: `ScrimmyClient.java`, `module/Category.java`, `module/Module.java`, `module/ModuleManager.java`.
- Layout and GUI: `gui/ClickGui.java`, shared `theme/PanelState.java`, new `util/PanelLayout.java`.
- Settings: shared `setting/Setting.java`, `ColorSetting.java`, and new `GroupSetting.java`, `ActionSetting.java`, `MultiSelectSetting.java`, `GradientSetting.java`.
- Persistence: `config/ConfigStore.java`, new `config/SettingCodec.java`.
- Hit effect: new `module/visual/HitParticles.java`, `render/HitParticleRenderer.java`, shared `particle/ParticleMath.java`, `ParticlePool.java`, and `shaders/core/hit_particle.vsh` / `.fsh`.
- Server damage hook: new `mixin/ClientPacketListenerMixin.java`, `scrimmyclient.mixins.json`, and `fabric.mod.json`.
- Validation/docs: `build.gradle`, `SettingsTest.java`, new `HitParticlesSettingsTest.java`, `ParticleMathTest.java`, `PanelLayoutTest.java`, `README.md`, and this checklist. `render/RoundedRenderer.java` gains smooth SDF gradient segments; AutoTotem and the blur renderer are retained.

# Verification performed

- Java 25 `./gradlew build`: 15 tests, zero failures/errors. The packaged mod includes both particle shaders and the mixin config; the temporary smoke harness is not packaged.
- GLSL compilation/link validation for the rounded GUI and particle shader pairs.
- Minecraft 1.21.11 + Fabric software-OpenGL launch: confirmed a melee burst, then live particles for all nine shapes and six built-in presets while cycling motion modes, depth modes, outlines, and trails. Clean disable/disconnect/shutdown.
- Captured the five-panel GUI, Visual settings, and ScrimmyClient+ at Minecraft scales 2/3 and client scales 0.75/1/1.5. Corrected a height constraint found during the smallest viewport transition; automatic layout coordinates now move together when columns change.

These checks do not establish hardware frame rates, Retina quality, multiplayer kill/critical attribution, audio quality, or compatibility with third-party renderers. Those remain in the checklist above.
