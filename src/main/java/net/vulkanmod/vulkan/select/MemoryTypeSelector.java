package net.vulkanmod.vulkan.select;

/**
 * Pure memory-type selection (no Vulkan calls), so it can be unit tested.
 * <p>
 * VulkanMod needs two pools: "GPU" memory for vertex/index/image data and "HOST" memory for staging and
 * frequently-written uniform data.
 * <ul>
 *   <li>GPU: a type that is exactly {@code DEVICE_LOCAL}; else {@code DEVICE_LOCAL | HOST_VISIBLE} (ReBAR/UMA, written
 *   directly); else the host pool (no device-local type at all, e.g. software rasterisers).</li>
 *   <li>HOST: exactly {@code HOST_VISIBLE | HOST_COHERENT | HOST_CACHED}; else any {@code HOST_VISIBLE | HOST_COHERENT}
 *   type (always present per the Vulkan spec).</li>
 * </ul>
 * The Fabric code ran its fallback pass even when the preferred GPU type had been found, which could replace it.
 */
public final class MemoryTypeSelector {
    public static final int DEVICE_LOCAL = 0x1;
    public static final int HOST_VISIBLE = 0x2;
    public static final int HOST_COHERENT = 0x4;
    public static final int HOST_CACHED = 0x8;

    private MemoryTypeSelector() {}

    public enum GpuKind { DEVICE_LOCAL, DEVICE_MAPPABLE, HOST_FALLBACK }

    public enum HostKind { CACHED, COHERENT }

    public record Selection(int gpuType, GpuKind gpuKind, int hostType, HostKind hostKind) {}

    /** @param typeFlags {@code propertyFlags} of each {@code VkMemoryType}, in index order */
    public static Selection select(int[] typeFlags) {
        int host = findExact(typeFlags, HOST_VISIBLE | HOST_COHERENT | HOST_CACHED);
        HostKind hostKind = HostKind.CACHED;
        if (host < 0) {
            host = findSuperset(typeFlags, HOST_VISIBLE | HOST_COHERENT);
            hostKind = HostKind.COHERENT;
        }
        if (host < 0)
            throw new SelectionException("No HOST_VISIBLE | HOST_COHERENT memory type (required by the Vulkan spec)");

        int gpu = findExact(typeFlags, DEVICE_LOCAL);
        GpuKind gpuKind = GpuKind.DEVICE_LOCAL;
        if (gpu < 0) {
            gpu = findSuperset(typeFlags, DEVICE_LOCAL | HOST_VISIBLE);
            gpuKind = GpuKind.DEVICE_MAPPABLE;
        }
        if (gpu < 0) {
            gpu = host;
            gpuKind = GpuKind.HOST_FALLBACK;
        }
        return new Selection(gpu, gpuKind, host, hostKind);
    }

    /**
     * Classic {@code findMemoryType}: first type allowed by {@code typeBits} (from {@code VkMemoryRequirements}) that
     * has all {@code required} property flags, or -1.
     */
    public static int find(int typeBits, int required, int[] typeFlags) {
        for (int i = 0; i < typeFlags.length; i++) {
            if ((typeBits & (1 << i)) != 0 && (typeFlags[i] & required) == required)
                return i;
        }
        return -1;
    }

    private static int findExact(int[] flags, int wanted) {
        for (int i = 0; i < flags.length; i++)
            if (flags[i] == wanted)
                return i;
        return -1;
    }

    private static int findSuperset(int[] flags, int wanted) {
        for (int i = 0; i < flags.length; i++)
            if ((flags[i] & wanted) == wanted)
                return i;
        return -1;
    }
}
