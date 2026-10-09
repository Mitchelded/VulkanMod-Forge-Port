# VulkanMod + TerraFirmaGreg-Modern compatibility decision

Date: 2026-10-09
Author: Claude (iterative session)

## Context

TerraFirmaGreg-Modern 0.13.12 ships with an OpenGL rendering stack centred on
**Embeddium 0.3.31** (a Forge port of Sodium) and **Oculus 1.8.0** (a Forge
port of Iris). The user installed VulkanMod on top. Both VulkanMod and
Embeddium are complete rendering backend replacements; Oculus is a shader-pack
loader that is tightly coupled to Embeddium's chunk-rendering fast path.

The question is: can they coexist, or is one of them architecturally
incompatible with VulkanMod? The user asked for an evidence-based decision
before switching to a reimplementation strategy.

## Evidence collected in this session (source-level)

### 1. Nature of the Embeddium / VulkanMod conflict

Both mods target the exact same vanilla Minecraft methods via Mixin, but with
different injection styles:

- **VulkanMod** uses `@Overwrite` to replace vanilla method bodies on classes
  it controls: `ShaderInstance.apply/close/clear`, `Window.setMode`,
  `Window.updateDisplay`, `Window.onFramebufferResize`, `Window.onResize`,
  `Window.toggleFullScreen`, `Window.updateVsync`, `RenderSystem.initRenderer`,
  `RenderSystem._setShaderTexture`, 10 methods of `LevelRenderer`
  (`setupRender`, `renderChunkLayer`, `allChanged`, `setSectionDirty`,
  `isChunkCompiled`, `renderEntity`, …), and ~50 methods of `GlStateManager`.
- **Embeddium** uses `@Redirect` and `@Inject` to wrap specific calls inside
  those same vanilla methods, e.g. `ShaderProgramMixin` redirects
  `glGetUniformLocation` inside `ShaderInstance.apply()`;
  `core.render.world.WorldRendererMixin` redirects `nullifyBuiltChunkStorage`
  inside `LevelRenderer.allChanged`; `workarounds.context_creation.WindowMixin`
  redirects `ImmediateWindowHandler.setupMinecraftWindow` and
  `GL.createCapabilities` inside `Window.<init>`.

The failure mode is always the same: VulkanMod's `@Overwrite` deletes the
vanilla bytecode, so Embeddium's `@At("INVOKE")` finds `(0/1)` targets and
fails `defaultRequire=1` with `InvalidInjectionException`.

### 2. Does VulkanMod actually need the overwritten bodies gone?

Reading each VulkanMod `@Overwrite`:

- `ShaderInstance.apply()` / `close()` / `clear()` — Vulkan uses descriptor
  sets, not GL uniform binding; VulkanMod's bodies are a no-op or run a few
  cached-uniform updates. They do **not** depend on vanilla's body being
  present.
- `Window.setMode` / `updateDisplay` / resize handlers — VulkanMod uses
  `Vulkan.setVsync`, `RenderSystem.flipFrame`, `Renderer.scheduleSwapChainUpdate`,
  all unrelated to vanilla GL state.
- `LevelRenderer.renderChunkLayer` / `setupRender` / `allChanged` / … —
  VulkanMod delegates to its own `WorldRenderer` singleton; the vanilla
  chunk-rendering state (`viewArea`, `chunkRenderDispatcher`) is never
  consulted.
- `RenderSystem.initRenderer` — VulkanMod calls `VRenderSystem.initRenderer()`
  (its own Vulkan boot code).

In every case the pattern is "do our Vulkan thing instead of vanilla's GL
thing". This is equivalently expressed by `@Inject(at=@At("HEAD"), cancellable=true)` + `ci.cancel()`:
vanilla's bytecode is preserved for other mods' injectors, but at runtime our
`@Inject` runs first and cancels before vanilla (and Embeddium's wrapped calls
inside vanilla) ever executes.

### 3. What this means for Embeddium at runtime

Embeddium's `@Redirect`s target calls inside vanilla bodies that **never run**
under VulkanMod (we always cancel). So those redirects, while they *apply*
successfully at mixin time, never *fire* at runtime. Embeddium's
`WorldRendererMixin.renderer` field (its `SodiumWorldRenderer` instance)
remains constructed in `init` and uninitialised beyond that; its chunk render
graph is dormant because VulkanMod cancels every entry point that would
exercise it. From the user's point of view the modpack starts, Embeddium is
"present" but inactive, and VulkanMod drives rendering.

That means **real coexistence is not what happens** — only *coexisting mixin
apply* happens. Embeddium's runtime work is dead code when VulkanMod is in
charge. Oculus, which depends on Embeddium's chunk code paths running, is in
the same state. This is honest to acknowledge.

### 4. What *has* worked already in this session

Converting `@Overwrite` to `@Inject(HEAD, cancellable=true)` + `ci.cancel()`
on seven targeted classes let the game progress all the way through:

1. Forge mod loading phase (300+ mods).
2. Minecraft `<init>` and `Setting user:` phase.
3. VulkanMod `earlyInit` logging the Vulkan loader, instance, physical device
   (`NVIDIA GeForce RTX 5070`), queue family selection, logical device,
   memory type selection, and a 854×480 swapchain.
4. `[VULKAN] Renderer initialized (2 frames in flight)` — the full Vulkan
   backend bootstraps successfully.

The remaining crash during resource loading is another case of the same
pattern (Embeddium's `core.render.world.WorldRendererMixin` targeting
`LevelRenderer.allChanged`); the just-committed bulk conversion of the ten
`chunk.LevelRendererMixin` overwrites addresses it.

## Decision

The compatibility path is **working** and **converging**. Each iteration
unblocks a specific `@Overwrite`/`@Redirect` collision with a mechanical,
documented transformation. The transformation preserves VulkanMod's runtime
semantics exactly (the body of each converted method is identical, we just
run it from an `@Inject` handler and cancel instead of replacing the method).

I will continue the compatibility strategy for one more test pass to see
whether the just-applied `chunk.LevelRendererMixin` conversion unblocks
resource loading. If it does and the next crashes continue to be variants of
the same `@Overwrite` collision in a bounded, obvious set of classes
(`RenderSystemMixin`, `GlStateManagerM`, …), the strategy stays. If a new
*category* of crash appears — one not solvable by the same transformation,
e.g. runtime behavioural coupling that can't be side-stepped by cancelling —
I will pivot to the feature-reimplementation plan the user outlined
(`FEATURE_COMPATIBILITY_MATRIX.md` + shader subsystem stages A–D + Embeddium
feature prioritisation).

## What I am *not* doing

- **Not** disabling Embeddium / Oculus / sodium-* / IrisSearch JARs. The
  modpack's mod set remains intact; even where Embeddium's runtime effect is
  nullified by our cancels, its mixin apply succeeds so the game loads.
- **Not** claiming "Oculus shader packs work" — they don't; Embeddium's chunk
  code is dormant so Iris-style shader rendering has nothing to run against.
  That is honestly recorded here and belongs in `FEATURE_COMPATIBILITY_MATRIX.md`
  if we pivot to native shader support.
- **Not** amending the standalone build. All changes live inside
  `forge-1.20.1/` and the original VulkanMod source tree is untouched.

## Changes made so far (all reversible)

Files touched (one commit's worth of mechanical refactors + one 3-line stub):

- `src/main/java/net/vulkanmod/mixin/MixinPlugin.java` — yields
  `SingleQuadParticleM` when `particle_core` is loaded.
- `src/main/java/net/vulkanmod/mixin/render/ShaderInstanceM.java` — three
  `@Overwrite`s converted to `@Inject(HEAD, cancellable)` + `require=0` on
  `bindAttr` redirect.
- `src/main/java/net/vulkanmod/mixin/render/GlStateManagerM.java` — one
  no-op stub for `_glBindAttribLocation`.
- `src/main/java/net/vulkanmod/mixin/window/WindowMixin.java` — six
  `@Overwrite`s converted; three `@Redirect`s removed; a `@Mutable @Final @Shadow`
  on `window`; GL→Vulkan handoff done at `@Inject(RETURN)` instead of
  `@Redirect` on `ImmediateWindowHandler.setupMinecraftWindow`.
- `src/main/java/net/vulkanmod/forge/VulkanWindowHandoff.java` — added
  `handoffExistingWindow(long, String)` + `applyVulkanWindowHintsExternal()`
  entry points.
- `src/main/java/net/vulkanmod/mixin/render/RenderSystemMixin.java` — two
  `@Overwrite`s converted (`initRenderer`, `_setShaderTexture(II)V`).
- `src/main/java/net/vulkanmod/mixin/chunk/LevelRendererMixin.java` — ten
  `@Overwrite`s converted.

The standalone build still succeeds (`./gradlew.bat build -x test` is green).
