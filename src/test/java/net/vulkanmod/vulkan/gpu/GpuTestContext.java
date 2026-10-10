package net.vulkanmod.vulkan.gpu;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The real VulkanMod Vulkan backend ({@link Vulkan#initVulkan}) on a hidden GLFW window, for GPU integration tests.
 * Skips the calling test when GLFW or a Vulkan device is unavailable. Uses the Khronos validation layer from
 * {@code ~/vulkanmod-ref/vvl} when present; tests assert that it reported no errors.
 */
final class GpuTestContext {
    private static final Path VALIDATION_LAYER_DIR = Path.of(System.getProperty("user.home"), "vulkanmod-ref", "vvl", "Library", "bin");

    private static boolean initialized;
    private static Throwable unavailable;

    private GpuTestContext() {
    }

    static synchronized void init() {
        if (!initialized && unavailable == null) {
            try {
                if (!glfwInit()) {
                    throw new IllegalStateException("glfwInit failed");
                }
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                long window = glfwCreateWindow(64, 64, "VulkanMod GPU test", MemoryUtil.NULL, MemoryUtil.NULL);
                if (window == MemoryUtil.NULL) {
                    throw new IllegalStateException("glfwCreateWindow failed");
                }

                Config config = new Config();
                if (Files.isRegularFile(VALIDATION_LAYER_DIR.resolve("VkLayer_khronos_validation.json"))) {
                    config.validation = true;
                    config.validationLayerPath = VALIDATION_LAYER_DIR.toString();
                }
                Initializer.CONFIG = config;
                Vulkan.initVulkan(window);
                initialized = true;
            } catch (Throwable t) {
                unavailable = t;
            }
        }
        assumeTrue(initialized, () -> "No Vulkan device for GPU tests: " + unavailable);
    }

    /** Records commands into VulkanMod's immediate command buffer, submits and waits. */
    static void submit(Consumer<VkCommandBuffer> commands) {
        VkCommandBuffer commandBuffer = Vulkan.beginImmediateCmd();
        try {
            commands.accept(commandBuffer);
        } finally {
            // never leave the shared immediate command buffer recording: a failing test must not break the next one
            Vulkan.endImmediateCmd();
        }
    }

    static SPIRVUtils.SPIRV glsl(String name, String source, SPIRVUtils.ShaderKind kind) {
        return SPIRVUtils.compileShader(name, source, kind);
    }

    /** Copies mip 0 of a color image (its current layout is restored afterwards) into host memory. */
    static ByteBuffer readback(VulkanImage image, int bytesPerPixel) {
        int size = image.width * image.height * bytesPerPixel;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_TO_CPU).flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            if (vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info) != VK_SUCCESS) {
                throw new IllegalStateException("readback buffer");
            }
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            int previousLayout = image.getCurrentLayout();
            submit(commandBuffer -> {
                try (MemoryStack inner = stackPush()) {
                    image.transitionImageLayout(inner, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, inner);
                    region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                            .imageExtent(e -> e.set(image.width, image.height, 1));
                    vkCmdCopyImageToBuffer(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
                    if (previousLayout != VK_IMAGE_LAYOUT_UNDEFINED) {
                        image.transitionImageLayout(inner, commandBuffer, previousLayout);
                    }
                }
            });

            vmaInvalidateAllocation(Vulkan.getAllocator(), allocation, 0, VK_WHOLE_SIZE);
            ByteBuffer copy = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
            MemoryUtil.memCopy(info.pMappedData(), MemoryUtil.memAddress(copy), size);
            vmaDestroyBuffer(Vulkan.getAllocator(), buffer, allocation);
            return copy;
        }
    }

    /** A host-visible buffer filled with {@code data}; returns {buffer, allocation}. Freed with {@link #free}. */
    static long[] hostBuffer(int usage, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(data.remaining()).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_CPU_TO_GPU).flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            if (vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info) != VK_SUCCESS) {
                throw new IllegalStateException("host buffer");
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), info.pMappedData(), data.remaining());
            vmaFlushAllocation(Vulkan.getAllocator(), pAllocation.get(0), 0, VK_WHOLE_SIZE);
            return new long[]{pBuffer.get(0), pAllocation.get(0)};
        }
    }

    static void free(long[] buffer) {
        vmaDestroyBuffer(Vulkan.getAllocator(), buffer[0], buffer[1]);
    }

    /** RGBA8 pixel at (x, row) as 0xRRGGBBAA. Row 0 is the first row in memory. */
    static int rgba8(ByteBuffer pixels, int width, int x, int row) {
        int i = (row * width + x) * 4;
        return (pixels.get(i) & 0xFF) << 24 | (pixels.get(i + 1) & 0xFF) << 16 | (pixels.get(i + 2) & 0xFF) << 8 | (pixels.get(i + 3) & 0xFF);
    }
}
