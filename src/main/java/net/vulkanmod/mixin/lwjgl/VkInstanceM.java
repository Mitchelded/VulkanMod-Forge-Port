package net.vulkanmod.mixin.lwjgl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Patches LWJGL 3.3.1's own {@code VkInstance} constructor helper.
 * <p>
 * Unlike LWJGL core (boot module layer, not transformable), the Vulkan bindings are bundled inside this mod jar and are
 * loaded through ModLauncher, so they can be patched. The original enumerates the device extensions of every
 * physical device into a {@code MemoryStack} buffer of {@code count * 260} bytes; on an RTX 5070 with driver 617.x
 * this exceeds the default 64 KiB stack and {@code new VkInstance(...)} throws
 * {@code OutOfMemoryError: Out of stack space}. Same logic, heap-allocated.
 */
@Mixin(value = VkInstance.class, remap = false)
public class VkInstanceM {

    /**
     * @author VulkanMod (Forge port)
     * @reason heap-allocate the device extension array (stack overflow with many device extensions)
     */
    @Overwrite
    private static Set<String> getAvailableDeviceExtensions(long instance) {
        HashSet<String> extensions = new HashSet<>();

        try (MemoryStack frame = MemoryStack.stackPush()) {
            IntBuffer ip = frame.callocInt(1);

            // VK.getGlobalCommands() is package-private; the function provider resolves the same loader entry point.
            long getInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
            long enumeratePhysicalDevices = callPPP(instance, memAddress(frame.ASCII("vkEnumeratePhysicalDevices")), getInstanceProcAddr);
            long enumerateDeviceExtensionProperties = callPPP(instance, memAddress(frame.ASCII("vkEnumerateDeviceExtensionProperties")), getInstanceProcAddr);
            if (enumeratePhysicalDevices == NULL || enumerateDeviceExtensionProperties == NULL)
                return extensions;

            int err = callPPPI(instance, memAddress(ip), NULL, enumeratePhysicalDevices);
            if (err != VK_SUCCESS || ip.get(0) == 0)
                return extensions;

            PointerBuffer physicalDevices = frame.mallocPointer(ip.get(0));
            err = callPPPI(instance, memAddress(ip), memAddress(physicalDevices), enumeratePhysicalDevices);
            if (err != VK_SUCCESS)
                return extensions;

            for (int i = 0; i < physicalDevices.remaining(); i++) {
                err = callPPPPI(physicalDevices.get(i), NULL, memAddress(ip), NULL, enumerateDeviceExtensionProperties);
                if (err != VK_SUCCESS || ip.get(0) == 0)
                    continue;

                VkExtensionProperties.Buffer deviceExtensions = VkExtensionProperties.malloc(ip.get(0));
                try {
                    err = callPPPPI(physicalDevices.get(i), NULL, memAddress(ip), deviceExtensions.address(), enumerateDeviceExtensionProperties);
                    if (err != VK_SUCCESS)
                        continue;

                    for (int j = 0; j < ip.get(0); j++) {
                        extensions.add(deviceExtensions.get(j).extensionNameString());
                    }
                } finally {
                    deviceExtensions.free();
                }
            }
        }

        return extensions;
    }
}
