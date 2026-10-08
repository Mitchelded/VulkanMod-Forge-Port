package net.vulkanmod.vulkan.select;

import java.util.List;

/**
 * Pure queue-family selection (no Vulkan calls), so it can be unit tested.
 * <p>
 * Policy (matches what VulkanMod relies on: a graphics queue, a present queue, an async compute queue and a transfer
 * queue, which may alias each other):
 * <ul>
 *   <li><b>graphics</b>: first family with {@code GRAPHICS}, preferring one that can also present (avoids a
 *   concurrent-sharing swapchain).</li>
 *   <li><b>present</b>: the graphics family if it can present, otherwise the first family that can.</li>
 *   <li><b>compute</b>: a dedicated compute family ({@code COMPUTE} without {@code GRAPHICS}), else any family with
 *   {@code COMPUTE} (the spec guarantees a graphics+compute family when graphics exists).</li>
 *   <li><b>transfer</b>: a dedicated transfer family (no graphics, no compute), else a non-graphics family that
 *   supports transfer, else the graphics family. Graphics and compute families implicitly support transfer operations
 *   even when {@code VK_QUEUE_TRANSFER_BIT} is not reported (Vulkan spec, "Queue Family Properties").</li>
 * </ul>
 * The Fabric implementation this replaces could throw from inside its transfer-fallback loop when family 0 did not
 * report the transfer bit, and used the compute family as "present" without checking present support.
 */
public final class QueueFamilySelector {
    public static final int GRAPHICS = 0x1;   // VK_QUEUE_GRAPHICS_BIT
    public static final int COMPUTE = 0x2;    // VK_QUEUE_COMPUTE_BIT
    public static final int TRANSFER = 0x4;   // VK_QUEUE_TRANSFER_BIT
    public static final int NONE = -1;

    private QueueFamilySelector() {}

    /** One {@code VkQueueFamilyProperties} entry plus whether it can present to the target surface. */
    public record Family(int queueFlags, int queueCount, boolean presentSupported) {
        boolean has(int bits) {
            return (queueFlags & bits) == bits && queueCount > 0;
        }

        boolean transferCapable() {
            return queueCount > 0 && (queueFlags & (GRAPHICS | COMPUTE | TRANSFER)) != 0;
        }
    }

    public record Selection(int graphics, int present, int compute, int transfer) {}

    public static Selection select(List<Family> families) {
        int graphics = NONE;
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i);
            if (f.has(GRAPHICS)) {
                if (graphics == NONE)
                    graphics = i;
                if (f.presentSupported()) {
                    graphics = i;
                    break;
                }
            }
        }
        if (graphics == NONE)
            throw new SelectionException("No queue family supports graphics operations");

        int present = families.get(graphics).presentSupported() ? graphics : NONE;
        for (int i = 0; i < families.size() && present == NONE; i++) {
            Family f = families.get(i);
            if (f.queueCount() > 0 && f.presentSupported())
                present = i;
        }
        if (present == NONE)
            throw new SelectionException("No queue family can present to the window surface");

        int compute = NONE;
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i);
            if (f.has(COMPUTE) && (f.queueFlags() & GRAPHICS) == 0) {
                compute = i;
                break;
            }
        }
        for (int i = 0; i < families.size() && compute == NONE; i++) {
            if (families.get(i).has(COMPUTE))
                compute = i;
        }
        if (compute == NONE)
            throw new SelectionException("No queue family supports compute operations");

        int transfer = NONE;
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i);
            if (f.has(TRANSFER) && (f.queueFlags() & (GRAPHICS | COMPUTE)) == 0) {
                transfer = i;
                break;
            }
        }
        for (int i = 0; i < families.size() && transfer == NONE; i++) {
            Family f = families.get(i);
            if (f.transferCapable() && (f.queueFlags() & GRAPHICS) == 0)
                transfer = i;
        }
        if (transfer == NONE)
            transfer = graphics;

        return new Selection(graphics, present, compute, transfer);
    }
}
