# Sub-project 2 — Multi-target rendering for shader-pack programs in VulkanMod

Date: 2026-10-10
Status: implemented on branch mrt-programs (2026-10-10); GPU tests + in-game smoke test pass
Overall project: Iris/OptiFine shader packs on this VulkanMod Forge port, through the `oculus-vulkan` fork
(`C:\Users\trety\Desktop\mods\oculus-vulkan`). Sub-project 1 (pack GLSL → Vulkan SPIR-V + `ProgramLayout`) is done.

## Goal

VulkanMod can render into offscreen targets with several color attachments, using programs supplied from outside as
SPIR-V, with the GL conventions sub-project 1 fixed, and this is proven on the real GPU by automated tests.

## Consumer needs (from Iris's GL layer)

Iris's `GlFramebuffer` binds up to 8 color textures (`colortexN`, any of ~20 formats) plus a depth texture;
`ExtendedShader`/`Program` draw with one program into them; later passes sample the same textures through texture
units; uniforms come from Iris's uniform system. Sub-project 1's `ProgramLayout` gives: one std140 block at binding 0
(may be absent), samplers at bindings 1..n, vertex inputs by name.

## Decisions

1. **Generalize, don't fork, the core classes.** `Framebuffer` and `RenderPass` get N color attachments; the existing
   single-attachment constructors and getters (`getColorAttachment()`, `getFormat()`) keep working, so vanilla
   rendering and `Renderer` integration are untouched. A parallel class hierarchy would duplicate `Renderer`'s
   bind/clear/viewport code.
2. **GL memory layout per framebuffer.** `Framebuffer.setGlLayout(true)`: viewport with positive height (row 0 =
   bottom, like GL), and pipelines created for render passes of such a framebuffer use `VK_FRONT_FACE_CLOCKWISE`,
   which equals GL's counter-clockwise front face in that orientation. Derived from `PipelineState.renderPass`, so
   no new pipeline-state bits.
3. **Pipelines.** `GraphicsPipeline` creates one blend attachment per color attachment of the render pass (same
   blend state and color mask for all, like `glBlendFunc`), supports an optional geometry stage, and can take vertex
   attribute locations by attribute name (shader-pack programs locate inputs by name, not by format order).
4. **Descriptor layouts** may have gaps in binding numbers (a program without loose uniforms starts at binding 1).
5. **Texture units** 12 → 32 (`VTextureSelector`), matching GL's `GL_MAX_TEXTURE_IMAGE_UNITS` on desktop drivers;
   packs sample colortex/depthtex/shadowtex/noisetex/… at once.
6. **Uniforms** use the existing `ManualUBO` (copies a caller-owned buffer each bind).

## Out of scope (moved)

- Terrain vertex format for packs → sub-project 4, together with Iris's block-id mapping that fills it.
- Per-attachment blend overrides (`blend.<program>.<buffer>`) → sub-project 4/5 where Iris drives them.
- Tessellation, storage images, SSBOs, compute → sub-project 6.
- Full-screen quad helper for composite passes → sub-project 5.

## Verification

GPU integration tests in VulkanMod's test suite: hidden GLFW window (no client API), real `Vulkan.initVulkan`
(skipped when no Vulkan device), then:
- an MRT pass (RGBA8 + RGBA16F + depth, GL layout) drawn with an external SPIR-V program; read back both
  attachments: each holds its own output; a triangle covering only NDC y < 0 lands in the first rows of memory
  (GL row order); a GL counter-clockwise triangle survives back-face culling;
- a second pass samples the first pass's attachment through a texture unit and scales it by a `ManualUBO` value;
- a program whose bindings start at 1 builds a valid layout; a program with a geometry stage draws.
All existing VulkanMod tests stay green, and the title screen still renders in the test instance (deploy loop).
