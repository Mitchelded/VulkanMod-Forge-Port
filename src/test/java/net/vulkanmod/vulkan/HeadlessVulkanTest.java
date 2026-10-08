package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.device.DeviceExtensions;
import net.vulkanmod.vulkan.select.GpuSelector;
import net.vulkanmod.vulkan.select.MemoryTypeSelector;
import net.vulkanmod.vulkan.select.QueueFamilySelector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Headless Vulkan bootstrap test on the real driver (no window, no Minecraft): instance, physical-device selection
 * through VulkanMod's selectors, logical device + queue, VMA, an image and a staging buffer, one command buffer that
 * clears the image and copies it back, fence synchronisation, and the pixel values. Skipped when no Vulkan loader or
 * device is present (CI without GPU).
 */
class HeadlessVulkanTest {
    private static VkInstance instance;
    private static VkPhysicalDevice physicalDevice;
    private static VkDevice device;
    private static int graphicsFamily;
    private static long allocator;

    @BeforeAll
    static void setUp() {
        try {
            VK.create();
        } catch (IllegalStateException alreadyCreated) {
            // LWJGL initialises Vulkan implicitly when VK is first touched; that's fine.
        } catch (Throwable t) {
            System.err.println("[VULKAN-TEST] Vulkan not available, skipping: " + t);
            t.printStackTrace();
            assumeTrue(false, "Vulkan loader not available: " + t);
        }

        System.out.println("[VULKAN-TEST] VK_ADD_LAYER_PATH=" + System.getenv("VK_ADD_LAYER_PATH"));
        validation = VulkanDiagnostics.instanceLayers().contains(Vulkan.VALIDATION_LAYER);
        System.out.println("[VULKAN-TEST] validation layer " + (validation ? "ENABLED" : "not installed (set VK_ADD_LAYER_PATH)"));

        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("VulkanMod headless test"))
                    .apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            VkDebugUtilsMessengerCreateInfoEXT dbg = null;
            if (validation) {
                ci.ppEnabledLayerNames(stack.pointers(stack.UTF8(Vulkan.VALIDATION_LAYER)));
                ci.ppEnabledExtensionNames(stack.pointers(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
                dbg = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback((severity, type, pData, user) -> {
                            String msg = VkDebugUtilsMessengerCallbackDataEXT.create(pData).pMessageString();
                            System.out.println("[VULKAN-VALIDATION] " + msg);
                            if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
                                VALIDATION_ERRORS.add(msg);
                            return VK_FALSE;
                        });
                ci.pNext(dbg.address()); // covers vkCreateInstance/vkDestroyInstance too
            }
            PointerBuffer p = stack.mallocPointer(1);
            int r = vkCreateInstance(ci, null, p);
            assumeTrue(r == VK_SUCCESS, "vkCreateInstance failed: " + r);
            // Exercises the patched-at-runtime code path too: LWJGL's VkInstance constructor enumerates every
            // device's extensions (this is what overflowed the 64 KiB MemoryStack on RTX 50-series in-game).
            instance = new VkInstance(p.get(0), ci);

            if (validation) {
                LongBuffer pMessenger = stack.mallocLong(1);
                assertEquals(VK_SUCCESS, EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, dbg, null, pMessenger));
                messenger = pMessenger.get(0);
            }
        }
    }

    private static boolean validation;
    private static long messenger;
    private static final List<String> VALIDATION_ERRORS = java.util.Collections.synchronizedList(new ArrayList<>());

    @AfterAll
    static void tearDown() {
        if (allocator != 0)
            vmaDestroyAllocator(allocator);
        if (device != null)
            vkDestroyDevice(device, null);
        if (messenger != 0)
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        if (instance != null)
            vkDestroyInstance(instance, null);
        // With the Khronos layer present this also checks resource lifetime (leaked objects at vkDestroyDevice).
        assertTrue(VALIDATION_ERRORS.isEmpty(), "validation errors: " + VALIDATION_ERRORS);
    }

    private static List<VkPhysicalDevice> physicalDevices() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer n = stack.ints(0);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer handles = stack.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, handles);
            List<VkPhysicalDevice> list = new ArrayList<>();
            for (int i = 0; i < n.get(0); i++)
                list.add(new VkPhysicalDevice(handles.get(i), instance));
            return list;
        }
    }

    @Test
    void loaderAndInstanceDiagnostics() {
        int api = VulkanDiagnostics.loaderApiVersion();
        assertTrue(VK_VERSION_MAJOR(api) >= 1);
        Set<String> ext = VulkanDiagnostics.instanceExtensions();
        assertTrue(ext.contains("VK_KHR_surface"), "loader must expose VK_KHR_surface: " + ext);
        System.out.println("[VULKAN-TEST] loader " + VulkanDiagnostics.version(api) + ", " + ext.size() + " instance extensions");
    }

    @Test
    void selectionAndClearReadback() {
        List<VkPhysicalDevice> devices = physicalDevices();
        assumeTrue(!devices.isEmpty(), "no Vulkan physical device");

        // ---- GPU selection with VulkanMod's selector, on real device data
        List<GpuSelector.Candidate> candidates = new ArrayList<>();
        try (MemoryStack stack = stackPush()) {
            for (VkPhysicalDevice d : devices) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(d, props);
                candidates.add(new GpuSelector.Candidate(props.deviceNameString(), props.deviceType()));
                Set<String> ext = DeviceExtensions.enumerate(d);  // heap enumeration (no stack overflow)
                assertTrue(ext.contains("VK_KHR_swapchain"), props.deviceNameString() + " lacks VK_KHR_swapchain");
                System.out.println("[VULKAN-TEST] device " + props.deviceNameString() + " type "
                        + GpuSelector.typeName(props.deviceType()) + ", " + ext.size() + " device extensions");
            }
        }
        physicalDevice = devices.get(GpuSelector.choose(candidates, -1).index());

        // ---- queue families (no surface: treat graphics families as "present capable")
        try (MemoryStack stack = stackPush()) {
            IntBuffer n = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.malloc(n.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, n, qf);
            List<QueueFamilySelector.Family> families = new ArrayList<>();
            for (int i = 0; i < n.get(0); i++)
                families.add(new QueueFamilySelector.Family(qf.get(i).queueFlags(), qf.get(i).queueCount(),
                        (qf.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0));
            graphicsFamily = QueueFamilySelector.select(families).graphics();
        }

        // ---- memory types through the selector
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mem);
            int[] flags = new int[mem.memoryTypeCount()];
            for (int i = 0; i < flags.length; i++)
                flags[i] = mem.memoryTypes(i).propertyFlags();
            MemoryTypeSelector.Selection sel = MemoryTypeSelector.select(flags);
            System.out.println("[VULKAN-TEST] memory selection " + sel);
        }

        // ---- logical device
        try (MemoryStack stack = stackPush()) {
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(graphicsFamily).pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qci);
            PointerBuffer p = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkCreateDevice(physicalDevice, dci, null, p));
            device = new VkDevice(p.get(0), physicalDevice, dci, VK12.VK_API_VERSION_1_2);
        }

        // ---- VMA
        try (MemoryStack stack = stackPush()) {
            VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(stack);
            fns.set(instance, device);
            VmaAllocatorCreateInfo aci = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(physicalDevice).device(device).instance(instance)
                    .pVulkanFunctions(fns).vulkanApiVersion(VK12.VK_API_VERSION_1_2);
            PointerBuffer p = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vmaCreateAllocator(aci, p));
            allocator = p.get(0);
        }

        final int W = 16, H = 16;
        try (MemoryStack stack = stackPush()) {
            // image (device local)
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(W, H, 1);
            VmaAllocationCreateInfo gpu = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pImageAlloc = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vmaCreateImage(allocator, ici, gpu, pImage, pImageAlloc, null));

            // staging buffer (host visible, mapped)
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size((long) W * H * 4).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo host = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_TO_CPU).flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pBufAlloc = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            assertEquals(VK_SUCCESS, vmaCreateBuffer(allocator, bci, host, pBuffer, pBufAlloc, info));

            // command pool + buffer
            LongBuffer pPool = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(graphicsFamily), null, pPool));
            PointerBuffer pCmd = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pPool.get(0)).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pCmd));
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            barrier(stack, cmd, pImage.get(0), range, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, 1f).float32(1, 0.5f).float32(2, 0f).float32(3, 1f);
            vkCmdClearColorImage(cmd, pImage.get(0), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
            barrier(stack, cmd, pImage.get(0), range, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, pImage.get(0), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pBuffer.get(0), region);
            assertEquals(VK_SUCCESS, vkEndCommandBuffer(cmd));

            // submit + fence
            LongBuffer pFence = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFence));
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);
            assertEquals(VK_SUCCESS, vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmd)), pFence.get(0)));
            assertEquals(VK_SUCCESS, vkWaitForFences(device, pFence, true, 5_000_000_000L));

            vmaInvalidateAllocation(allocator, pBufAlloc.get(0), 0, VK_WHOLE_SIZE);
            ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(info.pMappedData(), W * H * 4);
            for (int i = 0; i < W * H; i++) {
                assertEquals(255, pixels.get(i * 4) & 0xFF, "R at pixel " + i);
                assertEquals(128, pixels.get(i * 4 + 1) & 0xFF, 1, "G at pixel " + i);
                assertEquals(0, pixels.get(i * 4 + 2) & 0xFF, "B at pixel " + i);
                assertEquals(255, pixels.get(i * 4 + 3) & 0xFF, "A at pixel " + i);
            }

            // explicit resource lifetime: everything created here is destroyed here
            vkDestroyFence(device, pFence.get(0), null);
            vkDestroyCommandPool(device, pPool.get(0), null);
            vmaDestroyBuffer(allocator, pBuffer.get(0), pBufAlloc.get(0));
            vmaDestroyImage(allocator, pImage.get(0), pImageAlloc.get(0));
        }
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, VkImageSubresourceRange range,
                                int srcAccess, int dstAccess, int oldLayout, int newLayout) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).subresourceRange(range);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b);
    }
}
