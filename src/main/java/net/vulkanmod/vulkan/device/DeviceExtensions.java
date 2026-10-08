package net.vulkanmod.vulkan.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;

/**
 * Device extension enumeration on the native heap.
 * <p>
 * {@code VkExtensionProperties} is 260 bytes; recent drivers (e.g. NVIDIA 6xx on RTX 50-series) expose enough device
 * extensions that a single enumeration no longer fits into LWJGL's default 64 KiB thread {@link MemoryStack}, which
 * fails with {@code OutOfMemoryError: Out of stack space}. Never allocate the property array on the stack.
 */
public final class DeviceExtensions {
    private DeviceExtensions() {}

    public static Set<String> enumerate(VkPhysicalDevice device) {
        int count;
        try (MemoryStack stack = stackPush()) {
            IntBuffer pCount = stack.ints(0);
            if (vkEnumerateDeviceExtensionProperties(device, (String) null, pCount, null) != VK_SUCCESS)
                return Set.of();
            count = pCount.get(0);
        }
        if (count == 0)
            return Set.of();

        VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(count);
        try (MemoryStack stack = stackPush()) {
            IntBuffer pCount = stack.ints(count);
            // VK_INCOMPLETE (> 0) is acceptable: we still read the entries that were written.
            if (vkEnumerateDeviceExtensionProperties(device, (String) null, pCount, properties) < 0)
                return Set.of();

            int written = pCount.get(0);
            Set<String> names = new HashSet<>(written * 2);
            for (int i = 0; i < written; i++) {
                names.add(properties.get(i).extensionNameString());
            }
            return names;
        } finally {
            properties.free();
        }
    }
}
