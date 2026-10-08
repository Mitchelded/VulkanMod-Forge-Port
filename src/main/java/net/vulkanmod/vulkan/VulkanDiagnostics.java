package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceExtensions;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.select.GpuSelector;
import net.vulkanmod.vulkan.select.SwapchainSelector;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Human-readable Vulkan diagnostics, all tagged {@code [VULKAN]} in the game log.
 * <p>
 * Logged once at startup (loader, instance, devices, selection, swapchain) and dumped again in full when
 * initialisation fails, so a log alone is enough to understand why a machine cannot run the renderer.
 */
public final class VulkanDiagnostics {
    private static final Logger LOG = Initializer.LOGGER;

    /** Frame numbers that are logged in normal mode (no per-frame spam). */
    private static final long[] FRAME_MILESTONES = {1, 2, 3, 60, 600, 6000};
    private static final boolean LOG_EVERY_FRAME = Boolean.getBoolean("vulkanmod.logFrames");

    private static long framesRendered;

    private VulkanDiagnostics() {}

    // ------------------------------------------------------------------------------------------ loader / instance

    /** Returns the loader's instance-level API version, or 1.0 for a Vulkan 1.0 loader. */
    public static int loaderApiVersion() {
        try (MemoryStack stack = stackPush()) {
            if (VK.getFunctionProvider().getFunctionAddress("vkEnumerateInstanceVersion") == 0L)
                return VK_API_VERSION_1_0;
            IntBuffer version = stack.mallocInt(1);
            return VK11.vkEnumerateInstanceVersion(version) == VK_SUCCESS ? version.get(0) : VK_API_VERSION_1_0;
        }
    }

    public static Set<String> instanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkEnumerateInstanceExtensionProperties((String) null, count, null);
            Set<String> names = new TreeSet<>();
            if (count.get(0) == 0)
                return names;
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0)); // heap: may be large
            try {
                vkEnumerateInstanceExtensionProperties((String) null, count, props);
                for (int i = 0; i < count.get(0); i++)
                    names.add(props.get(i).extensionNameString());
            } finally {
                props.free();
            }
            return names;
        }
    }

    public static Set<String> instanceLayers() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkEnumerateInstanceLayerProperties(count, null);
            Set<String> names = new TreeSet<>();
            if (count.get(0) == 0)
                return names;
            VkLayerProperties.Buffer props = VkLayerProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceLayerProperties(count, props);
                for (int i = 0; i < count.get(0); i++)
                    names.add(props.get(i).layerNameString());
            } finally {
                props.free();
            }
            return names;
        }
    }

    public static void logLoader() {
        LOG.info("[VULKAN] Loader API version {}", version(loaderApiVersion()));
        Set<String> extensions = instanceExtensions();
        LOG.info("[VULKAN] Instance extensions ({}): {}", extensions.size(), String.join(", ", extensions));
        Set<String> layers = instanceLayers();
        LOG.info("[VULKAN] Instance layers ({}): {}", layers.size(), layers.isEmpty() ? "none" : String.join(", ", layers));
    }

    // ------------------------------------------------------------------------------------------ devices

    public static void logDevices(List<Device> available, List<Device> suitable) {
        LOG.info("[VULKAN] Physical devices: {} found, {} suitable", available.size(), suitable.size());
        for (int i = 0; i < available.size(); i++) {
            Device d = available.get(i);
            VkPhysicalDeviceProperties p = d.getProperties();
            LOG.info("[VULKAN]   #{} {} [{}] vendor=0x{} ({}) device=0x{} driver={} api={}{}",
                    i, d.deviceName, GpuSelector.typeName(p.deviceType()),
                    Integer.toHexString(p.vendorID()), d.vendorIdString, Integer.toHexString(p.deviceID()),
                    d.driverVersion, version(p.apiVersion()), suitable.contains(d) ? "" : " (NOT suitable)");
        }
    }

    public static void logSelectedDevice(Device device, boolean fromConfig) {
        VkPhysicalDeviceProperties p = device.getProperties();
        LOG.info("[VULKAN] Physical device selected: {} ({}; vendor 0x{}, device 0x{}, driver {}, Vulkan {})",
                device.deviceName, fromConfig ? "from VulkanMod settings" : "automatic",
                Integer.toHexString(p.vendorID()), Integer.toHexString(p.deviceID()), device.driverVersion,
                version(p.apiVersion()));
        Set<String> ext = DeviceExtensions.enumerate(device.getPhysicalDevice());
        LOG.info("[VULKAN] Device extensions: {} available; enabled: {}", ext.size(), Vulkan.REQUIRED_EXTENSION);
    }

    public static void logQueues(Queue.QueueFamilyIndices q) {
        LOG.info("[VULKAN] Graphics queue selected: family {}", q.graphicsFamily);
        LOG.info("[VULKAN] Present queue selected: family {}{}", q.presentFamily,
                q.presentFamily == q.graphicsFamily ? " (same as graphics)" : "");
        LOG.info("[VULKAN] Compute queue: family {}, transfer queue: family {}", q.computeFamily, q.transferFamily);
    }

    // ------------------------------------------------------------------------------------------ swapchain

    public static void logSwapchain(VkSurfaceCapabilitiesKHR caps, VkSurfaceFormatKHR.Buffer formats, IntBuffer presentModes,
                                    int chosenFormat, int chosenColorSpace, int chosenPresentMode, int width, int height,
                                    int imageCount) {
        List<String> modes = new ArrayList<>();
        for (int i = 0; i < presentModes.capacity(); i++)
            modes.add(SwapchainSelector.presentModeName(presentModes.get(i)));
        List<String> fmts = new ArrayList<>();
        for (int i = 0; i < formats.capacity(); i++)
            fmts.add(formats.get(i).format() + "/" + formats.get(i).colorSpace());

        LOG.info("[VULKAN] Surface capabilities: images {}..{}, current extent {}x{}, min {}x{}, max {}x{}",
                caps.minImageCount(), caps.maxImageCount() == 0 ? "unbounded" : caps.maxImageCount(),
                caps.currentExtent().width(), caps.currentExtent().height(),
                caps.minImageExtent().width(), caps.minImageExtent().height(),
                caps.maxImageExtent().width(), caps.maxImageExtent().height());
        LOG.info("[VULKAN] Surface formats (format/colorSpace): {}; present modes: {}", fmts, modes);
        LOG.info("[VULKAN] Swapchain created: {}x{}, {} images, format {} colorSpace {}, present mode {}",
                width, height, imageCount, chosenFormat, chosenColorSpace, SwapchainSelector.presentModeName(chosenPresentMode));
    }

    // ------------------------------------------------------------------------------------------ frames

    public static void onFrameSubmitted() {
        long n = ++framesRendered;
        if (LOG_EVERY_FRAME) {
            LOG.info("[VULKAN] Frame {} rendered", n);
            return;
        }
        for (long m : FRAME_MILESTONES) {
            if (n == m) {
                LOG.info("[VULKAN] Frame {} rendered", n);
                return;
            }
        }
    }

    public static long framesRendered() {
        return framesRendered;
    }

    // ------------------------------------------------------------------------------------------ failure dump

    /** Best-effort dump of everything known, used when initialisation fails. Never throws. */
    public static void dumpOnFailure(Throwable cause) {
        LOG.error("[VULKAN] ===== Vulkan initialisation failed: {} =====", cause.toString());
        try {
            logLoader();
        } catch (Throwable t) {
            LOG.error("[VULKAN] Vulkan loader not usable (is a Vulkan driver installed? vulkan-1.dll / libvulkan.so.1): {}", t.toString());
        }
        try {
            if (DeviceManager.availableDevices != null)
                logDevices(DeviceManager.availableDevices,
                        DeviceManager.suitableDevices != null ? DeviceManager.suitableDevices : List.of());
        } catch (Throwable t) {
            LOG.error("[VULKAN] Could not list devices: {}", t.toString());
        }
        LOG.error("[VULKAN] ===== end of Vulkan diagnostics =====");
    }

    public static String version(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }
}
