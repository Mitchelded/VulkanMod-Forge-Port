package net.vulkanmod.vulkan.select;

import java.util.List;

/**
 * Pure swapchain parameter selection (no Vulkan calls), so it can be unit tested.
 */
public final class SwapchainSelector {
    // Values from the Vulkan headers (VK10 / KHRSurface), duplicated so this class has no native dependency.
    public static final int FORMAT_R8G8B8A8_UNORM = 37;
    public static final int FORMAT_B8G8R8A8_UNORM = 44;
    public static final int COLOR_SPACE_SRGB_NONLINEAR = 0;

    public static final int PRESENT_MODE_IMMEDIATE = 0;
    public static final int PRESENT_MODE_MAILBOX = 1;
    public static final int PRESENT_MODE_FIFO = 2;
    public static final int PRESENT_MODE_FIFO_RELAXED = 3;

    public static final int UNDEFINED_EXTENT = 0xFFFFFFFF;

    private SwapchainSelector() {}

    public record SurfaceFormat(int format, int colorSpace) {}

    public record Extent(int width, int height) {
        public boolean isZero() {
            return width == 0 || height == 0;
        }
    }

    /**
     * Prefers {@code R8G8B8A8_UNORM}, then {@code B8G8R8A8_UNORM}, both in sRGB-nonlinear colour space; otherwise the
     * first reported format. UNORM (not SRGB) formats are wanted: Minecraft's shaders already output display-referred
     * colour, as they do with OpenGL's default framebuffer.
     */
    public static SurfaceFormat chooseSurfaceFormat(List<SurfaceFormat> available) {
        if (available.isEmpty())
            throw new SelectionException("The window surface reports no supported formats");

        SurfaceFormat chosen = available.get(0);
        for (SurfaceFormat f : available) {
            if (f.format() == FORMAT_R8G8B8A8_UNORM && f.colorSpace() == COLOR_SPACE_SRGB_NONLINEAR)
                return f;
            if (f.format() == FORMAT_B8G8R8A8_UNORM && f.colorSpace() == COLOR_SPACE_SRGB_NONLINEAR)
                chosen = f;
        }
        return chosen;
    }

    /**
     * VSync on: FIFO (the only mode every implementation must support). VSync off: the preferred uncapped mode if
     * supported, otherwise FIFO.
     */
    public static int choosePresentMode(boolean vsync, int preferredUncapped, int[] available) {
        if (vsync || preferredUncapped == PRESENT_MODE_FIFO)
            return PRESENT_MODE_FIFO;
        for (int mode : available) {
            if (mode == preferredUncapped)
                return mode;
        }
        return PRESENT_MODE_FIFO;
    }

    /**
     * Uses the surface's current extent when the platform defines it; otherwise (currentExtent = 0xFFFFFFFF) clamps the
     * framebuffer size to the supported range. A zero result means "minimised, do not create a swapchain".
     */
    public static Extent chooseExtent(Extent current, Extent min, Extent max, Extent framebuffer) {
        if (current.width() != UNDEFINED_EXTENT)
            return current;
        return new Extent(clamp(framebuffer.width(), min.width(), max.width()),
                clamp(framebuffer.height(), min.height(), max.height()));
    }

    /**
     * Requested image count, raised to the surface minimum and limited by the surface maximum (0 = no limit).
     * The Fabric implementation did not apply the maximum.
     */
    public static int chooseImageCount(int desired, int minImageCount, int maxImageCount) {
        int count = Math.max(desired, minImageCount);
        if (maxImageCount > 0)
            count = Math.min(count, maxImageCount);
        return count;
    }

    public static String presentModeName(int mode) {
        return switch (mode) {
            case PRESENT_MODE_IMMEDIATE -> "IMMEDIATE";
            case PRESENT_MODE_MAILBOX -> "MAILBOX";
            case PRESENT_MODE_FIFO -> "FIFO (VSync)";
            case PRESENT_MODE_FIFO_RELAXED -> "FIFO_RELAXED";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    private static int clamp(int v, int min, int max) {
        // Compare as unsigned: Vulkan extents are uint32.
        if (Integer.compareUnsigned(v, min) < 0)
            return min;
        if (Integer.compareUnsigned(v, max) > 0)
            return max;
        return v;
    }
}
