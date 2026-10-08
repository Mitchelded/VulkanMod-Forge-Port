package net.vulkanmod.vulkan.select;

import net.vulkanmod.vulkan.select.SwapchainSelector.Extent;
import net.vulkanmod.vulkan.select.SwapchainSelector.SurfaceFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.vulkanmod.vulkan.select.SwapchainSelector.*;
import static org.junit.jupiter.api.Assertions.*;

class SwapchainSelectorTest {

    @Test
    void constantsMatchVulkanHeaders() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM, FORMAT_R8G8B8A8_UNORM);
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM, FORMAT_B8G8R8A8_UNORM);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, COLOR_SPACE_SRGB_NONLINEAR);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR, PRESENT_MODE_IMMEDIATE);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR, PRESENT_MODE_MAILBOX);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR, PRESENT_MODE_FIFO);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR, PRESENT_MODE_FIFO_RELAXED);
    }

    @Test
    void prefersRgbaUnormSrgbNonlinear() {
        // Order reported by the RTX 5070 surface in latest.log: 44/0, 50/0, 37/0, ...
        SurfaceFormat chosen = chooseSurfaceFormat(List.of(
                new SurfaceFormat(44, 0), new SurfaceFormat(50, 0), new SurfaceFormat(37, 0), new SurfaceFormat(43, 0)));
        assertEquals(new SurfaceFormat(FORMAT_R8G8B8A8_UNORM, COLOR_SPACE_SRGB_NONLINEAR), chosen);
    }

    @Test
    void fallsBackToBgraUnorm() {
        SurfaceFormat chosen = chooseSurfaceFormat(List.of(new SurfaceFormat(50, 0), new SurfaceFormat(44, 0)));
        assertEquals(FORMAT_B8G8R8A8_UNORM, chosen.format());
    }

    @Test
    void fallsBackToFirstFormat() {
        SurfaceFormat first = new SurfaceFormat(97, 1000104002);
        assertEquals(first, chooseSurfaceFormat(List.of(first, new SurfaceFormat(64, 0))));
    }

    @Test
    void noFormatsIsAnError() {
        assertThrows(SelectionException.class, () -> chooseSurfaceFormat(List.of()));
    }

    @Test
    void vsyncAlwaysUsesFifo() {
        assertEquals(PRESENT_MODE_FIFO, choosePresentMode(true, PRESENT_MODE_IMMEDIATE,
                new int[]{PRESENT_MODE_IMMEDIATE, PRESENT_MODE_MAILBOX, PRESENT_MODE_FIFO}));
    }

    @Test
    void uncappedUsesPreferredWhenSupported() {
        assertEquals(PRESENT_MODE_IMMEDIATE, choosePresentMode(false, PRESENT_MODE_IMMEDIATE,
                new int[]{PRESENT_MODE_FIFO, PRESENT_MODE_IMMEDIATE}));
        assertEquals(PRESENT_MODE_MAILBOX, choosePresentMode(false, PRESENT_MODE_MAILBOX,
                new int[]{PRESENT_MODE_FIFO, PRESENT_MODE_MAILBOX}));
    }

    @Test
    void uncappedFallsBackToFifo() {
        assertEquals(PRESENT_MODE_FIFO, choosePresentMode(false, PRESENT_MODE_IMMEDIATE, new int[]{PRESENT_MODE_FIFO}));
    }

    @Test
    void extentUsesCurrentExtentWhenDefined() {
        Extent e = chooseExtent(new Extent(854, 480), new Extent(854, 480), new Extent(854, 480), new Extent(1, 1));
        assertEquals(new Extent(854, 480), e);
    }

    @Test
    void extentClampsFramebufferWhenUndefined() {
        Extent e = chooseExtent(new Extent(UNDEFINED_EXTENT, UNDEFINED_EXTENT), new Extent(1, 1), new Extent(4096, 4096),
                new Extent(5000, 300));
        assertEquals(new Extent(4096, 300), e);
    }

    @Test
    void zeroExtentMeansMinimised() {
        Extent e = chooseExtent(new Extent(0, 0), new Extent(0, 0), new Extent(0, 0), new Extent(0, 0));
        assertTrue(e.isZero());
    }

    @Test
    void imageCountRespectsSurfaceLimits() {
        assertEquals(3, chooseImageCount(3, 2, 8));    // RTX 5070: 2..8
        assertEquals(4, chooseImageCount(3, 4, 0));    // Mesa/RADV min 4, unbounded max
        assertEquals(2, chooseImageCount(3, 1, 2));    // max 2: the Fabric code requested 3 (invalid usage)
    }
}
