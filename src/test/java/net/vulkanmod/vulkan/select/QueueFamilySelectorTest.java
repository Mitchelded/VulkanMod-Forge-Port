package net.vulkanmod.vulkan.select;

import net.vulkanmod.vulkan.select.QueueFamilySelector.Family;
import net.vulkanmod.vulkan.select.QueueFamilySelector.Selection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.vulkanmod.vulkan.select.QueueFamilySelector.*;
import static org.junit.jupiter.api.Assertions.*;

class QueueFamilySelectorTest {

    private static Family f(int flags, boolean present) {
        return new Family(flags, 1, present);
    }

    @Test
    void nvidiaLayout() {
        // RTX 5070 / driver 617 as observed in latest.log: 0 = G|C|T + present, 1 = T, 2 = C|T, ...
        Selection s = select(List.of(
                f(GRAPHICS | COMPUTE | TRANSFER, true),
                f(TRANSFER, false),
                f(COMPUTE | TRANSFER, false),
                f(TRANSFER, false)));
        assertEquals(new Selection(0, 0, 2, 1), s);
    }

    @Test
    void amdLayoutWithAsyncComputeAndDma() {
        Selection s = select(List.of(
                f(GRAPHICS | COMPUTE | TRANSFER, true),
                f(COMPUTE | TRANSFER, true),
                f(TRANSFER, false)));
        assertEquals(new Selection(0, 0, 1, 2), s);
    }

    @Test
    void singleUniversalFamily() {
        // Typical Intel iGPU / mobile: one family does everything.
        Selection s = select(List.of(f(GRAPHICS | COMPUTE | TRANSFER, true)));
        assertEquals(new Selection(0, 0, 0, 0), s);
    }

    @Test
    void transferBitNotReportedOnGraphicsFamily() {
        // Graphics/compute families implicitly support transfer. The Fabric code threw
        // "Failed to find queue family with transfer support" here.
        Selection s = select(List.of(f(GRAPHICS | COMPUTE, true)));
        assertEquals(0, s.transfer());
    }

    @Test
    void prefersGraphicsFamilyThatCanPresent() {
        Selection s = select(List.of(
                f(GRAPHICS | COMPUTE | TRANSFER, false),
                f(GRAPHICS | COMPUTE | TRANSFER, true)));
        assertEquals(1, s.graphics());
        assertEquals(1, s.present());
    }

    @Test
    void separatePresentFamily() {
        Selection s = select(List.of(
                f(GRAPHICS | COMPUTE | TRANSFER, false),
                f(TRANSFER, true)));
        assertEquals(0, s.graphics());
        assertEquals(1, s.present());
    }

    @Test
    void noPresentSupportIsAnError() {
        // The Fabric code silently used the compute family as "present" here.
        SelectionException e = assertThrows(SelectionException.class,
                () -> select(List.of(f(GRAPHICS | COMPUTE | TRANSFER, false), f(COMPUTE, false))));
        assertTrue(e.getMessage().contains("present"));
    }

    @Test
    void noGraphicsIsAnError() {
        assertThrows(SelectionException.class, () -> select(List.of(f(COMPUTE | TRANSFER, true))));
    }

    @Test
    void familiesWithZeroQueuesAreIgnored() {
        Selection s = select(List.of(
                new Family(GRAPHICS | COMPUTE | TRANSFER, 0, true),
                f(GRAPHICS | COMPUTE | TRANSFER, true)));
        assertEquals(1, s.graphics());
    }

    @Test
    void emptyListIsAnError() {
        assertThrows(SelectionException.class, () -> select(List.of()));
    }
}
