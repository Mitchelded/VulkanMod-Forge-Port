package net.vulkanmod.vulkan.select;

import net.vulkanmod.vulkan.select.GpuSelector.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.vulkanmod.vulkan.select.GpuSelector.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuSelectorTest {

    private static final Candidate IGPU = new Candidate("AMD Radeon(TM) Graphics", TYPE_INTEGRATED_GPU);
    private static final Candidate DGPU = new Candidate("NVIDIA GeForce RTX 5070", TYPE_DISCRETE_GPU);
    private static final Candidate CPU = new Candidate("llvmpipe", TYPE_CPU);

    @Test
    void prefersDiscreteGpu() {
        assertEquals(1, choose(List.of(IGPU, DGPU), -1).index());
        assertEquals(0, choose(List.of(DGPU, IGPU), -1).index());
    }

    @Test
    void integratedBeforeOtherTypes() {
        assertEquals(1, choose(List.of(CPU, IGPU), -1).index());
    }

    @Test
    void otherWhenNothingElse() {
        assertEquals(0, choose(List.of(CPU), -1).index());
    }

    @Test
    void configuredIndexWins() {
        Choice c = choose(List.of(DGPU, IGPU), 1);
        assertEquals(1, c.index());
        assertTrue(c.fromConfig());
    }

    @Test
    void outOfRangeConfigFallsBackToAuto() {
        Choice c = choose(List.of(IGPU, DGPU), 7);
        assertEquals(1, c.index());
        assertFalse(c.fromConfig());
    }

    @Test
    void noSuitableDeviceIsAnError() {
        assertThrows(SelectionException.class, () -> choose(List.of(), -1));
    }

    @Test
    void constantsMatchVulkanHeaders() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, TYPE_DISCRETE_GPU);
        assertEquals(org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, TYPE_INTEGRATED_GPU);
        assertEquals(org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU, TYPE_CPU);
    }
}
