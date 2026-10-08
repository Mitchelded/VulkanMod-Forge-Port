# VulkanMod → Forge 1.20.1: porting analysis

## Source and target
- Supplied tree (`../src`) is upstream xCollateral/VulkanMod branch `1.20.x` HEAD (0.4.7_dev), targeting **MC 1.20.3/1.20.4 Fabric**, not 1.20.1.
  The last 1.20.1 upstream commit is `979905a`. The port uses the newer tree and fixes the 1.20.4 → 1.20.1 API differences.
- Target: Forge **47.4.13** (any 47.x), MC 1.20.1, Java 17, ForgeGradle 6, official mappings, Mixin 0.8.5, LWJGL **3.3.1**.

## How VulkanMod replaces OpenGL
1. **Window:** the GLFW window is created with `GLFW_NO_API`, and `GL.createCapabilities`/`glfwMakeContextCurrent` are redirected away. No GL context exists.
2. **Bootstrap:** `RenderSystem.initRenderer` is overwritten → `Vulkan.initVulkan`: instance, surface, device/queues, VMA, swapchain, renderer.
3. **GL emulation:** `GlStateManager`/`RenderSystem` are overwritten and route textures, buffers, framebuffers, blend/depth/cull state to Vulkan objects (`net.vulkanmod.gl.*`, `VRenderSystem`).
4. **Shaders:** `ShaderInstance`/`EffectInstance` are compiled from GLSL to SPIR-V (shaderc) into `GraphicsPipeline`s.
5. **World:** `LevelRenderer` chunk methods are overwritten by VulkanMod's own section graph, chunk builder, area buffers and indirect draws (`WorldRenderer`).
6. **Frame loop:** `MinecraftMixin` hooks `runTick`:
   - `beginFrame` acquires a swapchain image and begins the render pass;
   - `endFrame` submits and presents.
   `Window.updateDisplay` no longer swaps GL buffers.

## Forge-specific problems found and solved (all verified in game)
| Problem | Solution |
|---|---|
| LWJGL/JOML are in the boot module layer and can't be transformed (Fabric `GL11M/GL15M/GL30M/Matrix4fM`) | Equivalent `GlStateManager` overwrites, plus redirects at the 6 MC `setOrtho`/`setPerspective` call sites (`VkProjection`, [0,1] depth) |
| FML early window hands over a **GL** window via `ImmediateWindowHandler` (no `glfwCreateWindow` in `Window.<init>`) | `VulkanWindowHandoff`: destroy it and recreate it as `GLFW_NO_API`; vanilla `LoadingOverlay`; `ClientModLoaderM` stops the early-display tick (otherwise a blocking error box) |
| `GLFWVulkan` (boot layer) cannot see `VkInstance` (mod jar) | `GlfwVulkanBridge` calls the native GLFW functions via JNI |
| Shader resources read with `Paths.get(URI)`, which fails on `union:` URLs | Classpath streams |
| Mod constructors run after the window and Vulkan init | `Initializer.earlyInit()` is called from `WindowMixin` |
| Forge `ShaderInstance(…, ResourceLocation, …)` ctor; `ParticleEngine.render(+Frustum)`; Mixin 0.8.5 allows ctor @Inject only at RETURN | Retargeted mixins |
| Fabric API (fluids, shader registration, options screen) | `IClientFluidTypeExtensions`/`ForgeHooksClient.getFluidSprites`, `RegisterShadersEvent`, `ScreenEvent.Opening` |
| Forge `RenderLevelStageEvent` in `renderChunkLayer` | Re-dispatched in the overwrite |
| LWJGL 3.3.1 `VkInstance` overflows the 64 KiB stack on RTX 50 (291 device extensions) | `VkInstanceM` (bundled class, transformable) + heap enumeration |

## Bugs fixed vs upstream (found by tests or the validation layer)
- Swapchain present semaphores are now per image (sync validation error).
- Queue-family transfer fallback threw; present fallback was unchecked.
- Swapchain image count was not clamped to `maxImageCount`.
- Config: wrote a directory instead of the file; crashed on malformed JSON.
- `VulkanImage` destroyed `mainImageView` repeatedly instead of the mip views.
- Leaked images, render passes and pipelines at shutdown; frame ops deferred during shutdown never ran.
- shaderc include result was allocated on a popped stack frame.

## Known limitations
- Mods that call `GL11.*` etc. directly can't be intercepted on Forge (LWJGL can't be transformed) and will fail with "No GLCapabilities".
- Fabulous graphics is forced to Fancy (upstream limitation).
- Forge multi-render-type models and `ModelData` in VulkanMod's chunk builder were not audited; vanilla blocks render correctly.
- Remaining shutdown validation reports: `VkSampler` objects (`SamplerManager` cache) and one `VkRenderPass` are still not destroyed. These are cosmetic at exit.
- Exclusive fullscreen and the VSync toggle were not covered by automated tests.
