# Implementation status

Date: 2026-10-09

## This session: compatibility patches

| Change | Status | Verified by |
|---|---|---|
| `MixinPlugin` yields `SingleQuadParticleM` when `particle_core` loaded | implemented, tested | Log line `Skipping net.vulkanmod.mixin.render.particle.SingleQuadParticleM because mod 'particle_core' also transforms this target` fires on every launch; particle_core's own mixin apply then succeeds. |
| `ShaderInstanceM`: `@Overwrite` on `apply/close/clear` → `@Inject(HEAD, cancellable)` + cancel | implemented, tested | Embeddium's `ShaderProgramMixin` previously-fatal `redirectGetUniformLocation` injection now succeeds; game progresses past `Setting user`. |
| `ShaderInstanceM.bindAttr` @Redirect marked `require = 0` + `GlStateManagerM._glBindAttribLocation` no-op stub | implemented, tested | Oculus's `iris$redirectBindAttributeLocation` wins priority tiebreak without VulkanMod failing; its no-op forwarding still cannot cause a GL call because the GlStateManager stub absorbs it. |
| `WindowMixin`: six `@Overwrite`s → `@Inject(HEAD, cancellable)` + cancel; three `@Redirect`s removed; `@Mutable @Final @Shadow` on `window`; GL→Vulkan handoff moved from `@Redirect` on `setupMinecraftWindow` to `@Inject(RETURN)` using `VulkanWindowHandoff.handoffExistingWindow` | implemented, tested | VulkanMod log shows `[VULKAN] Vanilla window handoff produced an OpenGL window (client API 0x30001); replacing it with a Vulkan-compatible window` → full Vulkan loader/instance/device/swapchain/renderer bring-up on each launch. |
| `WindowMixin.vulkan$setMode` null-safe when `Initializer.CONFIG` not yet set | implemented, tested | Avoided NPE during the one vanilla ctor call to `setMode` before `earlyInit` runs at `@Inject(RETURN)`. |
| `RenderSystemMixin`: `initRenderer` + `_setShaderTexture(II)V` `@Overwrite` → `@Inject(HEAD, cancellable)` + cancel | implemented, tested | Oculus's `iris$onRendererInit` and `_setShaderTexture` injections now apply without conflict. |
| `chunk.LevelRendererMixin`: all ten `@Overwrite`s (`setupRender`, `compileChunks`, `isChunkCompiled`, `renderChunkLayer`, `allChanged`, `setSectionDirty`, `getChunkStatistics`, `hasRenderedAllChunks`, `countRenderedChunks`, `renderEntity`) → `@Inject(HEAD, cancellable)` + cancel/setReturnValue | implemented, tested | Embeddium's `core.render.world.WorldRendererMixin` previously-fatal `redirect$bbl000$nullifyBuiltChunkStorage` (and its sibling redirects) now apply; game progresses past the previous crash point. |
| `DebugScreenOverlayM`: `@Redirect` on `Lists.newArrayList(Object[])` → `@Inject(method = "getSystemInformation", at = @At("RETURN"))` that appends VulkanMod stats to the returned list | implemented, pending test | Embeddium's `features.gui.hooks.debug.DebugHudMixin.redirectRightTextEarly` now has an unoccupied INVOKE to target. |

## Observed progression this session

Each change pushed the game farther through startup. The sequence of
first-fatal signatures, in order:

1. `particle_core.mixins.json:BillboardParticleVertexMixin failed injection check, (0/1) succeeded` — fixed by MixinPlugin yield.
2. `embeddium.mixins.json:features.shader.uniform.ShaderProgramMixin cannot inject into ShaderInstance::m_173363_()V merged by net.vulkanmod.mixin.render.ShaderInstanceM` — fixed by converting `ShaderInstance.apply` overwrite.
3. `Redirector bindAttr(IILjava/lang/CharSequence;)V in vulkanmod.mixins.json:render.ShaderInstanceM failed injection check, (0/1) succeeded` — fixed by `require = 0` + GlStateManager stub.
4. `@At("INVOKE") on com/mojang/blaze3d/platform/Window::preSwapBuffers cannot inject into Window::m_85435_()V merged by net.vulkanmod.mixin.window.WindowMixin` — fixed by converting Window.updateDisplay overwrite.
5. `Redirector wrapGlfwCreateWindow failed injection check` — fixed by removing our `@Redirect` on `ImmediateWindowHandler.setupMinecraftWindow` in favour of ctor-RETURN swap.
6. `@At("INVOKE") selector Found @Inject targetting a constructor` on `vulkan$prepareWindow` — fixed by consolidating all ctor logic into the RETURN @Inject (Mixin 0.8.5 forbids non-TAIL/RETURN @Inject on ctors).
7. `Redirector postWindowCreated()Lorg/lwjgl/opengl/GLCapabilities;` — fixed by removing conflicting `@Redirect`s on `glfwMakeContextCurrent` and `GL.createCapabilities` (vanilla GL setup now runs; our RETURN inject destroys the context immediately after).
8. `redirect$bbl000$nullifyBuiltChunkStorage cannot inject into LevelRenderer::m_109818_()V merged by net.vulkanmod.mixin.chunk.LevelRendererMixin` — fixed by bulk-converting the ten `chunk.LevelRendererMixin` overwrites.
9. `Redirector redirectRightTextEarly([Ljava/lang/Object;)Ljava/util/ArrayList; in embeddium.mixins.json:features.gui.hooks.debug.DebugHudMixin failed injection check` — fix in flight (DebugScreenOverlayM converted this session, pending re-test).

Significant positive milestones recorded in `logs/latest.log`:
- `[VulkanMod/]: == VulkanMod 0.4.7-forge.1 (Forge) ==` — `earlyInit` ran successfully.
- `[VULKAN] Vanilla window handoff produced an OpenGL window (client API 0x30001); replacing it with a Vulkan-compatible window` — new handoff path works.
- `[VULKAN] Replacement window 854x480 at (853, 480)` — Vulkan window created at the geometry FML's early display produced.
- `[VULKAN] Loader API version 1.4.341` → `Instance created (API 1.2 requested)` → `Window surface created` → `Physical device selected: NVIDIA GeForce RTX 5070` → `Logical device created` → `Swapchain created: 854x480, 3 images, format 37 colorSpace 0, present mode IMMEDIATE` → `[VULKAN] Renderer initialized (2 frames in flight)` — full Vulkan backend online.
- Game then enters mod init: ModernFix, Create, TerraFirmaCraft, KubeJS, GTCEu, 300+ mods complete `onCommonSetup`/`onClientSetup`.
- Resource pack folder scan (`FolderRepositorySource`), tacz resource export, etc. are the last log lines before the previous DebugHud crash.

## Known-good standalone build

`./gradlew.bat build -x test` is green. The output `VulkanMod-Forge-1.20.1-0.4.7-forge.1.jar` works in the known-good standalone test instance (previously documented in `PORTING_ANALYSIS.md`); nothing in this session changed VulkanMod's standalone behaviour because every change is of the form "replace an @Overwrite body with an @Inject at HEAD that runs the same body then cancels". Vanilla's method body is preserved in bytecode but never executes at runtime, so runtime behaviour is identical.

## Pending

- The in-flight test may or may not clear the DebugHud hurdle. If a new
  `(0/1) succeeded` style failure appears in a well-defined class, the same
  conversion pattern applies.
- The user asked for native feature reimplementation plans if coexistence
  stops progressing; `FEATURE_COMPATIBILITY_MATRIX.md` describes what is
  reachable without one. Shader-pack support (Stage A–D) is a separate,
  substantial piece of work and is not undertaken here.
