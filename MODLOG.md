# MODLOG — VulkanMod Forge 1.20.1 port

## Facts (verified)
- Supplied source `../src` == upstream xCollateral/VulkanMod branch `1.20.x` HEAD (byte-identical modulo CRLF).
  It targets **MC 1.20.3/1.20.4**, Fabric, mod_version 0.4.7_dev, LWJGL bindings 3.3.2. NOT 1.20.1.
- Last upstream commit that targeted 1.20.1: `979905a` (2023-09-23, "Renamed methods"), right before `0244c6a Update to 1.20.2`.
  207 commits / ~17k lines between it and the supplied tree. Reference clone: `~/vulkanmod-ref/upstream`.
- Test instance: `D:\PrismLauncher\instances\1.20.1(1)` — Forge **47.4.13**, MC 1.20.1, LWJGL **3.3.1**, empty mods/saves.
  Java: `C:/Users/trety/AppData/Roaming/PrismLauncher/java/java-runtime-gamma/bin/javaw.exe`.
- GPU: NVIDIA GeForce RTX 5070, driver 617.14, `C:\Windows\System32\vulkan-1.dll` present. No Vulkan SDK (no validation layers).
- Dev JDK: Zulu 17.0.18. Forge MDK 47.4.13: ForgeGradle [6.0,6.2), Gradle 8.8, official mappings.
- Existing third-party "VulkanMod [Unofficial Forge Port]" (menoXD, GPL-3, 1.20.1) — no source link found.

## Fabric-specific code in supplied tree
- Initializer (ClientModInitializer, FabricLoader config dir/version)
- GameRendererMixin: Fabric CoreShaderRegistrationCallback / FabricShaderProgram
- VBO: @Environment
- LiquidRenderer: fabric-rendering-fluids FluidRenderHandlerRegistry

## Forge-vs-Fabric blockers identified
1. LWJGL + JOML live in BootstrapLauncher's boot module layer -> NOT transformable by ModLauncher mixins.
   Affects: GL11M, GL15M, GL30M (org.lwjgl.opengl.*), Matrix4fM (org.joml.Matrix4f).
2. FML early loading window (`earlyWindowControl=true` default) creates the GLFW window WITH a GL context and
   hands it to `Window.<init>` via `ImmediateWindowHandler.setupMinecraftWindow`. Vulkan surface creation needs a
   GLFW_NO_API window. ForgeLoadingOverlay draws with raw GL.
3. Access widener -> Forge access transformer (SRG names).

## Route
Port supplied 0.4.7 tree; compile against Forge 47.4.13; fix 1.20.4->1.20.1 API deltas using `979905a` as reference.
Project: `forge-1.20.1/` (separate Gradle build; original tree untouched).

## Milestone 1 (2026-10-08 21:51) — title screen rendered by Vulkan on RTX 5070
Fixes that were required, in the order the real game revealed them:
1. Mixin config lacked `"refmap"` -> "No refMap loaded" (Forge prod runs SRG names).
2. `ShaderInstanceM`: Forge adds `ShaderInstance(ResourceProvider, ResourceLocation, VertexFormat)`; bare `"<init>"`
   selects only the FIRST ctor (delegating one). Target the RL ctor explicitly.
3. `ParticleEngine.render` has an extra `Frustum` param on Forge.
4. LWJGL 3.3.1 `VkInstance.<init>` -> `getAvailableDeviceExtensions` stack-allocates count*260 B: RTX 5070 / 617.14
   overflows the 64 KiB MemoryStack (`OutOfMemoryError: Out of stack space`). Patched via mixin on the *bundled*
   `org.lwjgl.vulkan.VkInstance` (transformable because it is in the mod jar) + heap enumeration in Device/DeviceManager.
5. `GLFWVulkan` (boot layer, lwjgl-glfw) cannot link `org.lwjgl.vulkan.VkInstance` (game layer, mod jar):
   NoClassDefFoundError. Call native glfwCreateWindowSurface/glfwGetRequiredInstanceExtensions via JNI.
6. Shader sources were read via `Paths.get(new URI(resourceUrl))`; Forge resource URLs are `union:` -> read via
   `getResourceAsStream`. (Also fixed use-after-free in shaderc include callback.)
7. FML `ClientModLoader` passes `ImmediateWindowHandler::renderTick` to ModLoader; after our window swap it renders
   into the destroyed early window -> GLFW error -> blocking TinyFD message box (white window hang).
   `ClientModLoaderM` swaps the tick for glfwPollEvents.
8. Forge's Mixin 0.8.5 forbids @Inject into constructors except RETURN/TAIL (Fabric's fork allows it):
   ModelPartCubeM, EffectInstanceM reworked.
9. `terrain_Z.fsh` -> `terrain_z.fsh` (uppercase rejected by MC's pack scanner).

Tools: `tools/verify_mixins.py <mapped forge jar>` verifies all mixin targets offline (352 refs).
Screenshots: PrintWindow(PW_RENDERFULLCONTENT) script `~/vulkanmod-ref/tools/shot.ps1` (um win shot's ffmpeg lacks gfxcapture).
Deploy loop: `~/vulkanmod-ref/bin/deploy-run` (build, install into instance, launch via Prism, summarize log/crash).
