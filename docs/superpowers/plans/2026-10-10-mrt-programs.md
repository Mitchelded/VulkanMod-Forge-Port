# MRT + External Programs (Sub-project 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VulkanMod renders into multi-attachment offscreen targets with externally supplied SPIR-V programs in GL conventions, proven by GPU tests.

**Architecture:** Generalize `Framebuffer`/`RenderPass` to N color attachments (single-attachment API preserved), add a per-framebuffer GL-layout flag (positive viewport + clockwise front face), extend `GraphicsPipeline` (N blend attachments, geometry stage, attribute locations by name) and descriptor layouts (binding gaps), raise texture units to 32. Tests drive the real `Vulkan.initVulkan` with a hidden GLFW window.

**Tech Stack:** Java 17, ForgeGradle 6 (Forge 47.4.13), LWJGL 3.3.1 (Vulkan, VMA, shaderc, GLFW), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-10-mrt-programs-design.md`

## Global Constraints

- Vanilla rendering path unchanged: `Framebuffer.getColorAttachment()`, `getFormat()`, single-attachment builders keep their behavior; every existing test stays green.
- GL layout: positive-height viewport, `VK_FRONT_FACE_CLOCKWISE`; default (VulkanMod) layout: negative-height viewport, `VK_FRONT_FACE_COUNTER_CLOCKWISE`.
- Texture units: `VTextureSelector.SIZE = 32`.
- GPU tests skip (assumption) when Vulkan or GLFW is unavailable.
- Build/test with `export JAVA_HOME='C:\Users\trety\miniconda3\Library\lib\jvm'`.

## Review Focus

- `Renderer.setViewport` (GL `glViewport` emulation) while a GL-layout framebuffer is bound must also use positive height.
- `Renderer.clearAttachments` with an MRT framebuffer clears every color attachment, not only attachment 0.
- `RenderPass` with N color attachments ending in `SHADER_READ_ONLY_OPTIMAL` records each attachment's new layout.
- Framebuffer resize/cleanUp frees all owned color attachments and leaves borrowed images alone.
- A pipeline built against a 1-attachment render pass and used with a 3-attachment one must not be reused (state key includes the render pass).

---

### Task 1: GPU test harness
**Files:** Create `src/test/java/net/vulkanmod/vulkan/gpu/GpuTestContext.java`, `src/test/java/net/vulkanmod/vulkan/gpu/MultiTargetRenderingTest.java`.
- Hidden GLFW window (`GLFW_CLIENT_API = NO_API`, `GLFW_VISIBLE = false`), `Initializer.CONFIG = new Config()`, `Vulkan.initVulkan(window)`; helpers: `record(Consumer<VkCommandBuffer>)` via `Vulkan.beginImmediateCmd/endImmediateCmd`, `readback(VulkanImage) → ByteBuffer` (copy to host-visible buffer).
- First test: existing single-attachment `Framebuffer` + `RenderPass` with CLEAR load op; read back the clear color.

### Task 2: N color attachments
**Files:** Modify `vulkan/framebuffer/Framebuffer.java`, `vulkan/framebuffer/RenderPass.java`, `vulkan/shader/GraphicsPipeline.java`, `vulkan/Renderer.java` (clearAttachments).
- `Framebuffer.builder(List<VulkanImage> colors, VulkanImage depth)`; `getColorAttachments()`; render pass with one `AttachmentInfo` per color attachment; transitions and final layouts for all; pipeline blend attachment count = render pass color count.
- Test: RGBA8 + RGBA16F + depth, program writes different constants to locations 0 and 1; read back both.

### Task 3: GL layout
**Files:** `Framebuffer.java` (`setGlLayout`, `viewport`), `GraphicsPipeline.java` (front face), `Renderer.java` (`setViewport`).
- Tests: triangle in NDC y < 0 covers the first memory rows; GL-CCW triangle with back-face culling is drawn.

### Task 4: External program features
**Files:** `GraphicsPipeline.java`, `Pipeline.java` (Builder: geometry SPIR-V, attribute locations by name; descriptor layout with binding gaps).
- Tests: program with bindings starting at 1; program with a geometry stage; vertex attributes located by name.

### Task 5: Texture units + sampling pass
**Files:** `vulkan/texture/VTextureSelector.java`.
- Test: pass 2 samples pass 1's attachment through texture unit 20 and multiplies by a `ManualUBO` float; read back.

### Task 6: Regression
- Full `./gradlew test`; build jar; in-game smoke test with `~/vulkanmod-ref/bin/deploy-run` on the `1.20.1(1)` instance (title screen renders, no Vulkan errors in log).
