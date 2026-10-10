package net.vulkanmod.vulkan.gpu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
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

    /** A texture holding one color, in SHADER_READ_ONLY layout, bound to {@code unit}. */
    static VulkanImage solidTexture(float r, float g, float b, int unit) {
        VulkanImage image = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer framebuffer = Framebuffer.builder(image, null).build();
        RenderPass renderPass = RenderPass.builder(framebuffer).build();
        VRenderSystem.setClearColor(r, g, b, 1.0f);
        submit(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer, renderPass, stack);
                renderPass.endRenderPass(commandBuffer);
                image.readOnlyLayout(stack, commandBuffer);
            }
        });
        VTextureSelector.bindTexture(unit, image);
        return image;
    }

    static VulkanImage drawWith(GraphicsPipeline pipeline) {
        VulkanImage image = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer framebuffer = Framebuffer.builder(List.of(image), null).build().setGlLayout(true);
        draw(framebuffer, RenderPass.builder(framebuffer).build(), pipeline);
        return image;
    }

    static int centerPixel(VulkanImage image) {
        ByteBuffer pixels = readback(image, 4);
        int pixel = rgba8(pixels, SIZE, SIZE / 2, SIZE / 2);
        MemoryUtil.memFree(pixels);
        return pixel;
    }

    @Test
    void bindingNumbersMayStartAboveZero() {
        // shader-pack programs without loose uniforms have no block at binding 0, their samplers start at 1
        solidTexture(1.0f, 0.0f, 1.0f, 5);
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE);
        builder.setUniforms(new ArrayList<>(), new ArrayList<>(List.of(new ImageDescriptor(1, "sampler2D", "tex", 5))));
        builder.setSPIRVs(glsl("test.vsh", FULLSCREEN_TRIANGLE, SPIRVUtils.ShaderKind.VERTEX_SHADER), glsl("test.fsh", """
                #version 450
                layout(set = 0, binding = 1) uniform sampler2D tex;
                layout(location = 0) out vec4 color;
                void main() { color = texture(tex, vec2(0.5)); }
                """, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));

        assertEquals(0xFF00FFFF, centerPixel(drawWith(builder.createGraphicsPipeline())));
    }

    @Test
    void programsMayHaveAGeometryStage() {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE);
        ManualUBO ubo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 4);
        ubo.setSrc(MemoryUtil.memAddress(UNIFORMS), 16);
        builder.setUniforms(new ArrayList<>(List.of(ubo)), new ArrayList<>());
        builder.setSPIRVs(glsl("test.vsh", FULLSCREEN_TRIANGLE, SPIRVUtils.ShaderKind.VERTEX_SHADER), glsl("test.fsh", """
                #version 450
                layout(location = 0) in vec4 fromGeometry;
                layout(location = 0) out vec4 color;
                void main() { color = fromGeometry; }
                """, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        builder.setGeometrySPIRV(glsl("test.gsh", """
                #version 450
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                layout(location = 0) out vec4 fromGeometry;
                void main() {
                    for (int i = 0; i < 3; i++) { fromGeometry = vec4(0.0, 0.0, 1.0, 1.0); gl_Position = gl_in[i].gl_Position; EmitVertex(); }
                    EndPrimitive();
                }
                """, SPIRVUtils.ShaderKind.GEOMETRY_SHADER));

        assertEquals(0x0000FFFF, centerPixel(drawWith(builder.createGraphicsPipeline())));
    }

    @Test
    void vertexAttributesCanBeLocatedByName() {
        // shader-pack programs get their input locations by name, unrelated to the vertex format's element order
        Pipeline.Builder builder = new Pipeline.Builder(DefaultVertexFormat.POSITION_COLOR);
        ManualUBO ubo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 4);
        ubo.setSrc(MemoryUtil.memAddress(UNIFORMS), 16);
        builder.setUniforms(new ArrayList<>(List.of(ubo)), new ArrayList<>());
        builder.setAttributeLocations(java.util.Map.of("Position", 2, "Color", 5));
        builder.setSPIRVs(glsl("test.vsh", """
                #version 450
                layout(location = 5) in vec4 Color;
                layout(location = 2) in vec3 Position;
                layout(location = 0) out vec4 vertexColor;
                void main() { vertexColor = Color; gl_Position = vec4(Position, 1.0); }
                """, SPIRVUtils.ShaderKind.VERTEX_SHADER), glsl("test.fsh", """
                #version 450
                layout(location = 0) in vec4 vertexColor;
                layout(location = 0) out vec4 color;
                void main() { color = vertexColor; }
                """, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        GraphicsPipeline pipeline = builder.createGraphicsPipeline();

        // one big triangle, vertices = vec3 position + RGBA8 color (POSITION_COLOR, 16 bytes)
        ByteBuffer vertices = MemoryUtil.memAlloc(3 * 16).order(java.nio.ByteOrder.nativeOrder());
        float[][] positions = {{-1, -1, 0}, {3, -1, 0}, {-1, 3, 0}};
        for (float[] p : positions) {
            vertices.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).put((byte) 0).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        }
        vertices.flip();
        long[] vertexBuffer = hostBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, vertices);

        VulkanImage image = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer framebuffer = Framebuffer.builder(List.of(image), null).build().setGlLayout(true);
        RenderPass renderPass = RenderPass.builder(framebuffer).build();
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
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer[0]), stack.longs(0));
                vkCmdDraw(commandBuffer, 3, 1, 0, 0);
                renderPass.endRenderPass(commandBuffer);
            }
        });
        free(vertexBuffer);
        MemoryUtil.memFree(vertices);

        assertEquals(0x00FFFFFF, centerPixel(image));
    }

    @Test
    void aLaterPassSamplesAnEarlierAttachmentThroughAHighTextureUnit() {
        // pass 1: two attachments that end the render pass ready for sampling (like Iris's colortex between passes)
        VulkanImage first = target(VK_FORMAT_R8G8B8A8_UNORM);
        VulkanImage second = target(VK_FORMAT_R8G8B8A8_UNORM);
        Framebuffer gbuffers = Framebuffer.builder(List.of(first, second), null).build().setGlLayout(true);
        RenderPass.Builder passBuilder = RenderPass.builder(gbuffers);
        passBuilder.getColorAttachmentInfos().forEach(info -> info.setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        draw(gbuffers, passBuilder.build(), program(FULLSCREEN_TRIANGLE, """
                #version 450
                layout(location = 0) out vec4 a;
                layout(location = 1) out vec4 b;
                void main() { a = vec4(1.0); b = vec4(1.0, 0.0, 1.0, 1.0); }
                """));
        assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, first.getCurrentLayout());
        assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, second.getCurrentLayout());

        // pass 2: sample attachment 1 through texture unit 20, scale it by a uniform
        VTextureSelector.bindTexture(20, second);
        UNIFORMS.put(0, 0.5f);
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE);
        ManualUBO ubo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 4);
        ubo.setSrc(MemoryUtil.memAddress(UNIFORMS), 16);
        builder.setUniforms(new ArrayList<>(List.of(ubo)), new ArrayList<>(List.of(new ImageDescriptor(1, "sampler2D", "colortex1", 20))));
        builder.setSPIRVs(glsl("test.vsh", FULLSCREEN_TRIANGLE, SPIRVUtils.ShaderKind.VERTEX_SHADER), glsl("test.fsh", """
                #version 450
                layout(std140, set = 0, binding = 0) uniform U { vec4 values; };
                layout(set = 0, binding = 1) uniform sampler2D colortex1;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(texture(colortex1, vec2(0.5)).rgb * values.x, 1.0); }
                """, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));

        int pixel = centerPixel(drawWith(builder.createGraphicsPipeline()));
        UNIFORMS.put(0, 0.0f);
        // 0.5 * 255 = 127.5: UNORM conversion may round either way
        assertEquals(0x80, pixel >>> 24, 1, "red = 1.0 * 0.5");
        assertEquals(0x00, pixel >>> 16 & 0xFF);
        assertEquals(0x80, pixel >>> 8 & 0xFF, 1, "blue = 1.0 * 0.5");
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
