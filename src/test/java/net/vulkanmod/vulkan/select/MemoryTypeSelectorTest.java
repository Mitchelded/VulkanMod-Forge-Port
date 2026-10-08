package net.vulkanmod.vulkan.select;

import net.vulkanmod.vulkan.select.MemoryTypeSelector.GpuKind;
import net.vulkanmod.vulkan.select.MemoryTypeSelector.HostKind;
import net.vulkanmod.vulkan.select.MemoryTypeSelector.Selection;
import org.junit.jupiter.api.Test;

import static net.vulkanmod.vulkan.select.MemoryTypeSelector.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTypeSelectorTest {
    private static final int DL = DEVICE_LOCAL, HV = HOST_VISIBLE, HC = HOST_COHERENT, CA = HOST_CACHED;

    @Test
    void constantsMatchVulkanHeaders() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, DEVICE_LOCAL);
        assertEquals(org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, HOST_VISIBLE);
        assertEquals(org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, HOST_COHERENT);
        assertEquals(org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT, HOST_CACHED);
    }

    @Test
    void nvidiaDesktop() {
        // RTX 5070 log: GPU = type 1 (DEVICE_LOCAL), HOST = type 3 (CACHED)
        Selection s = select(new int[]{0, DL, HV | HC, HV | HC | CA, DL | HV | HC});
        assertEquals(new Selection(1, GpuKind.DEVICE_LOCAL, 3, HostKind.CACHED), s);
    }

    @Test
    void reBarDoesNotReplaceDeviceLocal() {
        // A DEVICE_LOCAL|HOST_VISIBLE type listed first must not win over plain DEVICE_LOCAL.
        Selection s = select(new int[]{DL | HV | HC, DL, HV | HC | CA});
        assertEquals(1, s.gpuType());
        assertEquals(GpuKind.DEVICE_LOCAL, s.gpuKind());
    }

    @Test
    void unifiedMemoryUsesMappableDeviceMemory() {
        // Integrated GPUs often only expose DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT(|CACHED)
        Selection s = select(new int[]{DL | HV | HC, DL | HV | HC | CA});
        assertEquals(GpuKind.DEVICE_MAPPABLE, s.gpuKind());
        assertEquals(0, s.gpuType());
        assertEquals(HostKind.COHERENT, s.hostKind());
    }

    @Test
    void noDeviceLocalFallsBackToHost() {
        Selection s = select(new int[]{HV | HC});
        assertEquals(GpuKind.HOST_FALLBACK, s.gpuKind());
        assertEquals(s.hostType(), s.gpuType());
    }

    @Test
    void missingHostCoherentIsAnError() {
        assertThrows(SelectionException.class, () -> select(new int[]{DL, HV}));
    }

    @Test
    void findRespectsTypeBitsAndFlags() {
        int[] types = {DL, HV | HC, HV | HC | CA};
        assertEquals(1, find(0b111, HV | HC, types));
        assertEquals(2, find(0b100, HV | HC, types));
        assertEquals(-1, find(0b001, HV, types));
    }
}
