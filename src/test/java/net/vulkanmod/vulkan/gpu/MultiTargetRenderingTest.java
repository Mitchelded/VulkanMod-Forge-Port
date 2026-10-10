package net.vulkanmod.vulkan.gpu;

import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.vulkan.gpu.GpuTestContext.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Offscreen rendering through VulkanMod's own Framebuffer / RenderPass / GraphicsPipeline on the real GPU. */
class MultiTargetRenderingTest {
    static final int SIZE = 16;
    private int validationErrorsBefore;

    @BeforeAll
    static void gpu() {
        GpuTestContext.init();
    }

    @BeforeEach
    void rememberValidationErrors() {
        validationErrorsBefore = Vulkan.VALIDATION_ERRORS.get();
    }

    @AfterEach
    void noValidationErrors() {
        assertEquals(validationErrorsBefore, Vulkan.VALIDATION_ERRORS.get(), "Vulkan validation errors, see test output");
    }

    static VulkanImage target(int format) {
        return VulkanImage.builder(SIZE, SIZE).setFormat(format)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .setClamp(true).createVulkanImage();
    }

    static final String FULLSCREEN_TRIANGLE = """
            #version 450
            void main() {
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** Values of the test programs' uniform block {@code layout(binding = 0) uniform U { vec4 values; };}. */
    static final FloatBuffer UNIFORMS = MemoryUtil.memCallocFloat(4);

    static GraphicsPipeline program(String vertex, String fragment) {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE);
        ManualUBO ubo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 4);
        ubo.setSrc(MemoryUtil.memAddress(UNIFORMS), 16);
        builder.setUniforms(new ArrayList<>(List.of(ubo)), new ArrayList<>());
        builder.setSPIRVs(glsl("test.vsh", vertex, SPIRVUtils.ShaderKind.VERTEX_SHADER),
                glsl("test.fsh", fragment, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        return builder.createGraphicsPipeline();
    }

    static void draw(Framebuffer framebuffer, RenderPass renderPass, GraphicsPipeline pipeline) {
        VRenderSystem.disableCull();
        VRenderSystem.disableDepthTest();
        submit(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer, renderPass, stack);
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(PipelineState.getCurrentPipelineState(renderPass)));
                pipeline.bindDescriptorSets(commandBuffer, Renderer.getDrawer().getUniformBuffer(), 0);
                vkCmdSetViewport(commandBuffer, 0, framebuffer.viewport(stack));
                vkCmdSetScissor(commandBuffer, 0, framebuffer.scissor(stack));
                vkCmdSetDepthBias(commandBuffer, 0.0f, 0.0f, 0.0f);
                vkCmdDraw(commandBuffer, 3, 1, 0, 0);
                renderPass.endRenderPass(commandBuffer);
            }
        });
    }

    static short half(ByteBuffer pixels, int index) {
        return pixels.getShort(index * 2);
    }

    @Test
    void writesEveryColorAttachmentOfAMultiTargetFramebuffer() {
        VulkanImage first = target(VK_FORMAT_R8G8B8A8_UNORM);
        VulkanImage second = target(VK_FORMAT_R16G16B16A16_SFLOAT);
        VulkanImage depth = VulkanImage.createDepthImage(Vulkan.getDefaultDepthFormat(), SIZE, SIZE,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, false, true);
        Framebuffer framebuffer = Framebuffer.builder(List.of(first, second), depth).build();
        RenderPass renderPass = RenderPass.builder(framebuffer).build();
        assertEquals(2, renderPass.getColorAttachmentCount());

        draw(framebuffer, renderPass, program(FULLSCREEN_TRIANGLE, """
                #version 450
                layout(location = 0) out vec4 a;
                layout(location = 1) out vec4 b;
                void main() { a = vec4(0.0, 1.0, 0.0, 1.0); b = vec4(0.25, 0.5, 0.75, 1.0); }
                """));

        ByteBuffer firstPixels = readback(first, 4);
        assertEquals(0x00FF00FF, rgba8(firstPixels, SIZE, 0, 0));
        assertEquals(0x00FF00FF, rgba8(firstPixels, SIZE, SIZE - 1, SIZE - 1));
        ByteBuffer secondPixels = readback(second, 8);
        // half floats 0.25, 0.5, 0.75, 1.0
        assertEquals(List.of((short) 0x3400, (short) 0x3800, (short) 0x3A00, (short) 0x3C00),
                List.of(half(secondPixels, 0), half(secondPixels, 1), half(secondPixels, 2), half(secondPixels, 3)));
        MemoryUtil.memFree(firstPixels);
        MemoryUtil.memFree(secondPixels);
    }

    @Test
    void glClearInsideARenderPassClearsEveryColorAttachment() {
        VulkanImage first = target(VK_FORMAT_R8G8B8A8_UNORM);
        VulkanImage second = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer framebuffer = Framebuffer.builder(List.of(first, second), null).build();
        RenderPass renderPass = RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).build();

        VRenderSystem.setClearColor(0.0f, 0.0f, 1.0f, 1.0f);
        submit(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer, renderPass, stack);
                Renderer.clearAttachments(commandBuffer, framebuffer, 0x4000 /* GL_COLOR_BUFFER_BIT */);
                renderPass.endRenderPass(commandBuffer);
            }
        });

        for (VulkanImage image : List.of(first, second)) {
            ByteBuffer pixels = readback(image, 4);
            assertEquals(0x0000FFFF, rgba8(pixels, SIZE, SIZE / 2, SIZE / 2));
            MemoryUtil.memFree(pixels);
        }
    }

    static final String NDC_HALVES = """
            #version 450
            layout(location = 0) in float ndcY;
            layout(location = 0) out vec4 color;
            void main() { color = ndcY < 0.0 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 1.0, 0.0, 1.0); }
            """;

    static final String FULLSCREEN_TRIANGLE_WITH_NDC_Y = """
            #version 450
            layout(location = 0) out float ndcY;
            void main() {
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
                ndcY = p.y;
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;

    @Test
    void glLayoutStoresTheBottomOfClipSpaceInTheFirstMemoryRows() {
        VulkanImage gl = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer glFramebuffer = Framebuffer.builder(List.of(gl), null).build().setGlLayout(true);
        draw(glFramebuffer, RenderPass.builder(glFramebuffer).build(), program(FULLSCREEN_TRIANGLE_WITH_NDC_Y, NDC_HALVES));
        ByteBuffer glPixels = readback(gl, 4);
        assertEquals(0xFF0000FF, rgba8(glPixels, SIZE, 0, 0), "row 0 = clip-space bottom, like OpenGL");
        assertEquals(0x00FF00FF, rgba8(glPixels, SIZE, 0, SIZE - 1));
        MemoryUtil.memFree(glPixels);

        // VulkanMod's own targets keep row 0 = top
        VulkanImage vk = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer vkFramebuffer = Framebuffer.builder(List.of(vk), null).build();
        draw(vkFramebuffer, RenderPass.builder(vkFramebuffer).build(), program(FULLSCREEN_TRIANGLE_WITH_NDC_Y, NDC_HALVES));
        ByteBuffer vkPixels = readback(vk, 4);
        assertEquals(0x00FF00FF, rgba8(vkPixels, SIZE, 0, 0));
        MemoryUtil.memFree(vkPixels);
    }

    /** A GL counter-clockwise (front) triangle and a clockwise one, both covering the center, with back faces culled. */
    static String centerTriangle(boolean counterClockwise) {
        String vertices = counterClockwise ? "vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)"
                : "vec2(-1.0, -1.0), vec2(-1.0, 3.0), vec2(3.0, -1.0)";
        return "#version 450\nvoid main() { vec2 p[3] = vec2[3](" + vertices + "); gl_Position = vec4(p[gl_VertexIndex], 0.0, 1.0); }\n";
    }

    static final String WHITE = """
            #version 450
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
            """;

    @Test
    void glLayoutKeepsOpenGlFrontFacesWithBackFaceCulling() {
        for (boolean glLayout : new boolean[]{true, false}) {
            for (boolean counterClockwise : new boolean[]{true, false}) {
                VulkanImage image = target(VK_FORMAT_R8G8B8A8_UNORM);
                Framebuffer framebuffer = Framebuffer.builder(List.of(image), null).build().setGlLayout(glLayout);
                VRenderSystem.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                drawCulled(framebuffer, RenderPass.builder(framebuffer).build(), program(centerTriangle(counterClockwise), WHITE));
                ByteBuffer pixels = readback(image, 4);
                assertEquals(counterClockwise ? 0xFFFFFFFF : 0, rgba8(pixels, SIZE, SIZE / 2, SIZE / 2),
                        "glLayout=" + glLayout + " counterClockwise=" + counterClockwise);
                MemoryUtil.memFree(pixels);
            }
        }
    }

    static void drawCulled(Framebuffer framebuffer, RenderPass renderPass, GraphicsPipeline pipeline) {
        VRenderSystem.enableCull();
        VRenderSystem.disableDepthTest();
        submit(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer, renderPass, stack);
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(PipelineState.getCurrentPipelineState(renderPass)));
                pipeline.bindDescriptorSets(commandBuffer, Renderer.getDrawer().getUniformBuffer(), 0);
                vkCmdSetViewport(commandBuffer, 0, framebuffer.viewport(stack));
                vkCmdSetScissor(commandBuffer, 0, framebuffer.scissor(stack));
                vkCmdSetDepthBias(commandBuffer, 0.0f, 0.0f, 0.0f);
                vkCmdDraw(commandBuffer, 3, 1, 0, 0);
                renderPass.endRenderPass(commandBuffer);
            }
        });
        VRenderSystem.disableCull();
    }

    @Test
    void clearsASingleAttachmentFramebuffer() {
        VulkanImage color = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer framebuffer = Framebuffer.builder(color, null).build();
        RenderPass renderPass = RenderPass.builder(framebuffer).build();

        VRenderSystem.setClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        submit(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer, renderPass, stack);
                renderPass.endRenderPass(commandBuffer);
            }
        });

        ByteBuffer pixels = readback(color, 4);
        assertEquals(0xFF0000FF, rgba8(pixels, SIZE, 0, 0));
        MemoryUtil.memFree(pixels);
    }
}
