# Feature compatibility matrix

Date: 2026-10-09
Scope: TerraFirmaGreg-Modern 0.13.12 on Forge 1.20.1 with VulkanMod 0.4.7-forge.1

The entries below describe what each mod *provides* and what state it ends up
in under the current VulkanMod compatibility strategy
(see `COMPATIBILITY_DECISION.md`). The strategy preserves vanilla bytecode via
`@Inject(HEAD, cancellable=true)` so other mods' Mixins apply cleanly, then
VulkanMod cancels before vanilla runs. That means other mods are *present at
mixin level* but their *runtime handlers do not execute* when VulkanMod has
replaced the host rendering path. The matrix is honest about that.

Legend:
- ✅ feature currently functional through VulkanMod's native path
- 🟡 feature's mixin applies but runtime handler is dormant (vanilla path
     cancelled by VulkanMod); the user-visible effect is absent
- ❌ feature is broken by VulkanMod and not yet reimplemented
- ⚪ feature untouched by VulkanMod — mod handles it itself

## Embeddium 0.3.31 (Sodium fork)

The matrix tracks the Sodium feature set as published by this build. Not every
item exists in Sodium 0.5.x upstream; verified against the shipped jar.

| Feature | State | Note |
|---|---|---|
| Chunk/terrain rendering (RenderSectionManager, SodiumWorldRenderer) | 🟡 | VulkanMod's `WorldRenderer` drives chunk rendering via its own section graph; Embeddium's chunk code is unreachable because `LevelRenderer.renderChunkLayer` / `setupRender` / `allChanged` are cancelled before vanilla's bytecode (and thus Embeddium's redirects inside it) run. |
| Chunk visibility / frustum culling | 🟡 | Embeddium's frustum mixin applies but produces no effect at runtime for the same reason. VulkanMod has its own frustum-based visibility via `WorldRenderer.getSectionGrid()` (used already, including by our `SingleQuadParticleM` cull). |
| Chunk rebuild scheduling | 🟡 | Embeddium's `ChunkBuilderMixin` — unreachable; VulkanMod has `TaskDispatcher`/`RenderRegionBuilder`. |
| Render-layer / material handling | 🟡 | Embeddium's `features.render.model.*` applies, but material routing is through VulkanMod's `RenderType` overwrites. |
| Entity / block-entity render speedups | 🟡 | Embeddium's `features.render.entity.*` mixins (shadow/cull/fast_render) apply but their helpers run against inactive vanilla paths. VulkanMod's own `EntityRendererM` and `LevelRendererMixin.renderEntity` carry the entity path (incl. optional entity culling via `Config.entityCulling`). |
| Options UI (overlays / render_layers / weather) | 🟡 | Mixins apply; options may be exposed in Embeddium's UI but have no effect under VulkanMod. |
| F3 debug HUD augmentation | 🟡 → in progress | `features.gui.hooks.debug.DebugHudMixin` currently conflicts with `DebugScreenOverlayM.redirectList`. Fix below (make our redirect non-required + append VulkanMod stats via @Inject RETURN) lets both mods' overlays combine. |
| Workarounds (NVIDIA WGL context sharing, GLFW event loop) | 🟡 | Mixins apply; the workarounds target OpenGL contexts that do not exist under Vulkan, so they are a no-op by construction. |
| Config options (sodium-options.json) | ⚪ | Config file lives in `config/`; Embeddium parses it independently. Options that would drive OpenGL render paths have no effect; options that are purely data (e.g. render distance tweaks exposed through its UI) still write to Minecraft's options. |

### What this means practically

For the user: Embeddium is installed but doesn't drive rendering. VulkanMod
drives rendering. The modpack starts and runs, but any Embeddium-specific
optimisation is dormant. The practical performance of the rendering pipeline
is VulkanMod's, which is already a Vulkan-native optimised chunk renderer.

## Oculus 1.8.0 (Iris fork, shader-pack support)

Oculus is Iris ported to Forge/Embeddium. Shader-pack rendering requires
Iris's pipeline, which assumes OpenGL framebuffers, GL program objects and
Embeddium's chunk render path.

| Feature | State | Note |
|---|---|---|
| Shader pack discovery / selection UI | 🟡 | Oculus's screens open and list packs in `shaderpacks/`, but selecting a pack has no effect because the shader pipeline cannot bind to VulkanMod's Vulkan descriptor sets. |
| GLSL preprocessing / Iris pack format parsing | 🟡 | The parser runs; the output targets a GL pipeline that doesn't exist. |
| GL program compilation | ❌ | Requires an active GL context — VulkanMod destroys the GL context during window handoff. |
| Shadow maps / deferred passes / composite stages | ❌ | Require Iris's FBO manager and GL rendering, both unavailable. |
| Entity / block-entity shaders, water / translucent passes | ❌ | Same reason. |
| Shader resource reload | ❌ | — |
| Framebuffer management | ❌ | VulkanMod uses its own `RenderPass`/attachment setup. |

### Reimplementation estimate (if the user requests it)

Native Vulkan shader-pack support in VulkanMod is a substantial subsystem.
Stages A–D from the user directive are a realistic plan:

- Stage A (shader module creation, SPIR-V compilation via `lwjgl-shaderc` which VulkanMod already bundles, pipeline cache, descriptor management) is largely in place: `net.vulkanmod.vulkan.shader.*` already handles GLSL→SPIR-V. Infrastructure cost ≈ small.
- Stage B (pack discovery, pack metadata parsing matching Iris's `.properties` + `shaders.properties`) is new code; ≈ few hundred LoC.
- Stage C (world rendering with pack shaders — translating `gbuffers_terrain`, `gbuffers_entities`, `gbuffers_hand`, `gbuffers_water` to Vulkan graphics pipelines, mapping Iris uniforms to Vulkan UBO/push constants) is the bulk of the work. GLSL dialect differences (`gl_FragData`, `texture2D`, draw buffer attachments) need a translator.
- Stage D (shadow maps, MRTs, deferred composites, post FX) is more code again.

Not committed to in this session; recorded here so the user can decide.

## Supporting rendering mods

| Mod | Features | State |
|---|---|---|
| **sodiumdynamiclights** (`sodiumdynamiclights-forge-1.0.10-1.20.1.jar`) | Dynamic entity/item light sources via Sodium's chunk graph. | 🟡 — Depends on Embeddium being the active chunk renderer; dormant under VulkanMod. Would need a VulkanMod-side dynamic light hook (small: a per-section light override in `WorldRenderer`). |
| **sodiumextras** (`sodiumextras-forge-1.0.7-1.20.1.jar`) | Borderless window, F11 toggle, cloud height, darkness, entity distance, font shadow, FPS counter overlay, language, fog toggle. | 🟡 — Mixins apply after this session's Window changes. Borderless window toggle targets `Window.setMode` which VulkanMod now @Inject-cancels; the `glfwSetWindowMonitor` redirect inside applies but never fires. User can achieve borderless by VulkanMod's existing `Config.windowedFullscreen`. |
| **sodiumoptionsapi** (`sodiumoptionsapi-forge-1.0.10-1.20.1.jar`) | Sodium/Embeddium options API surface. | ⚪ — Options UI only; no runtime effect once Embeddium is dormant. |
| **IrisSearch** (`IrisSearch-1.8.2-forge.jar`) | Search in Oculus shader list. | ⚪ — UI only; works for discovery but selected shader won't render under VulkanMod. |
| **iris_shader_folder** (`iris_shader_folder-1.4.2-forge.jar`) | Shaderpack folder opener. | ⚪ — Pure UX. |
| **BadOptimizations** (`BadOptimizations-2.4.1-1.20.1.jar`) | Various bytecode optimisations unrelated to rendering backend. | ✅ — Not touched by VulkanMod; works. |
| **particle_core** (`particle_core-0.3.3+1.20.1+forge.jar`) | Particle optimisations (`BillboardParticle*` mixins). | ✅ — Resolved cleanly by `MixinPlugin` yielding `SingleQuadParticleM`; particle_core drives particles. |
| **aaa_particles** | Extra particles mod. | ⚪ — Doesn't touch VulkanMod targets. |
| **immersive_optimization** | Various non-render perf tweaks. | ⚪ |
| **dynamic-fps** | FPS limiter when unfocused. | ⚪ — Driven by game loop, not rendering backend. |

## Immediate next feasibility

If the user accepts "Embeddium/Oculus present but dormant under VulkanMod":
- No more invasive changes are needed beyond finishing the remaining
  `@Overwrite` → `@Inject` conversions in the ~5-10 methods Embeddium's
  remaining mixins target. The game then boots to the title screen with
  VulkanMod rendering and the modpack's non-rendering mods functional.
- Shader packs will not render — this is honestly recorded above.

If the user wants shader support, the plan in sections 4–5 of their directive
(COMPATIBILITY_DECISION.md section "What I am not doing") applies: that is a
multi-day piece of work and is not attempted in this session.
