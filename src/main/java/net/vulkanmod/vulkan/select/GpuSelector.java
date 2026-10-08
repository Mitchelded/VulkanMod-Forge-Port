package net.vulkanmod.vulkan.select;

import java.util.List;

/**
 * Pure physical-device selection (no Vulkan calls), so it can be unit tested.
 * <p>
 * Order: the device chosen in VulkanMod's settings (index into the list of <i>suitable</i> devices, {@code -1} = auto),
 * then the first discrete GPU, then the first integrated GPU, then any other suitable device. No vendor is special-cased.
 */
public final class GpuSelector {
    public static final int TYPE_OTHER = 0;           // VK_PHYSICAL_DEVICE_TYPE_OTHER
    public static final int TYPE_INTEGRATED_GPU = 1;
    public static final int TYPE_DISCRETE_GPU = 2;
    public static final int TYPE_VIRTUAL_GPU = 3;
    public static final int TYPE_CPU = 4;

    private GpuSelector() {}

    public record Candidate(String name, int deviceType) {}

    public record Choice(int index, boolean fromConfig) {}

    public static Choice choose(List<Candidate> suitable, int configuredIndex) {
        if (suitable.isEmpty())
            throw new SelectionException("No GPU supports the features VulkanMod needs (graphics + present queue, "
                    + "VK_KHR_swapchain, a usable surface format and present mode)");

        if (configuredIndex >= 0 && configuredIndex < suitable.size())
            return new Choice(configuredIndex, true);

        int integrated = -1, other = -1;
        for (int i = 0; i < suitable.size(); i++) {
            int type = suitable.get(i).deviceType();
            if (type == TYPE_DISCRETE_GPU)
                return new Choice(i, false);
            if (type == TYPE_INTEGRATED_GPU && integrated < 0)
                integrated = i;
            else if (other < 0)
                other = i;
        }
        return new Choice(integrated >= 0 ? integrated : other, false);
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_INTEGRATED_GPU -> "integrated";
            case TYPE_DISCRETE_GPU -> "discrete";
            case TYPE_VIRTUAL_GPU -> "virtual";
            case TYPE_CPU -> "cpu";
            default -> "other";
        };
    }
}
