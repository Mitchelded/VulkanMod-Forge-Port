package net.vulkanmod.vulkan.gpu;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

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
