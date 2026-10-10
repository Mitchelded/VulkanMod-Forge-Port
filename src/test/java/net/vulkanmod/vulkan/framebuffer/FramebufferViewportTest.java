package net.vulkanmod.vulkan.framebuffer;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * glViewport / glScissor rectangles (origin bottom-left) mapped onto a 100x50 framebuffer in both memory layouts.
 * Pure math: no Vulkan objects, so loading this class can't initialize device-dependent statics before the GPU tests.
 */
class FramebufferViewportTest {
    private static List<Float> rect(VkViewport viewport) {
        return List.of(viewport.x(), viewport.y(), viewport.width(), viewport.height());
    }

    private static List<Integer> rect(VkRect2D scissor) {
        return List.of(scissor.offset().x(), scissor.offset().y(), scissor.extent().width(), scissor.extent().height());
    }

    @Test
    void vulkanModLayoutFlipsTheViewportAndTheScissor() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertEquals(List.of(10f, 25f, 30f, -20f), rect(Framebuffer.viewport(stack, false, 10, 5, 30, 20).get(0)));
            assertEquals(List.of(10, 25, 30, 20), rect(Framebuffer.scissor(stack, false, 50, 10, 5, 30, 20).get(0)));
        }
    }

    @Test
    void glLayoutUsesGlCoordinatesDirectly() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertEquals(List.of(10f, 5f, 30f, 20f), rect(Framebuffer.viewport(stack, true, 10, 5, 30, 20).get(0)));
            assertEquals(List.of(10, 5, 30, 20), rect(Framebuffer.scissor(stack, true, 50, 10, 5, 30, 20).get(0)));
        }
    }

    @Test
    void wholeFramebufferViewportIsUnchangedForVulkanModTargets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertEquals(List.of(0f, 50f, 100f, -50f), rect(Framebuffer.viewport(stack, false, 0, 0, 100, 50).get(0)));
        }
    }
}
