package net.vulkanmod.vulkan.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameDeferredQueueTest {

    /** Simulates the renderer: initFrame(slot) waits the slot's fence, drains it, then records into it. */
    @Test
    void resourcesAreReleasedOnlyWhenTheirSlotIsReused() {
        FrameDeferredQueue<String> q = new FrameDeferredQueue<>(2);
        List<String> freed = new ArrayList<>();

        q.setCurrentSlot(0);
        q.drain(0, freed::add);
        q.defer("bufferA");           // released while recording frame slot 0

        q.setCurrentSlot(1);
        q.drain(1, freed::add);       // slot 1's fence: bufferA may still be in use by slot 0
        assertTrue(freed.isEmpty());
        q.defer("bufferB");

        q.setCurrentSlot(0);
        q.drain(0, freed::add);       // slot 0's fence signalled -> bufferA is safe
        assertEquals(List.of("bufferA"), freed);

        q.setCurrentSlot(1);
        q.drain(1, freed::add);
        assertEquals(List.of("bufferA", "bufferB"), freed);
        assertEquals(0, q.pending());
    }

    @Test
    void drainAllReleasesEverySlot() {
        // Regression: Fabric's freeAllBuffers() released images of the current slot only.
        FrameDeferredQueue<Integer> q = new FrameDeferredQueue<>(3);
        for (int slot = 0; slot < 3; slot++) {
            q.setCurrentSlot(slot);
            q.defer(slot);
        }
        q.setCurrentSlot(1);
        List<Integer> freed = new ArrayList<>();
        q.drainAll(freed::add);
        assertEquals(List.of(0, 1, 2), freed);
        assertEquals(0, q.pending());
    }

    @Test
    void workDeferredWhileDrainingWaitsAFullCycle() {
        FrameDeferredQueue<Runnable> q = new FrameDeferredQueue<>(2);
        List<String> log = new ArrayList<>();
        q.setCurrentSlot(0);
        q.defer(() -> {
            log.add("op1");
            q.defer(() -> log.add("op2")); // e.g. a frame op that frees a buffer
        });

        q.drain(0, Runnable::run);
        assertEquals(List.of("op1"), log);
        assertEquals(1, q.pending(0));

        q.drain(0, Runnable::run);
        assertEquals(List.of("op1", "op2"), log);
    }

    @Test
    void invalidSlotsAreRejected() {
        FrameDeferredQueue<Object> q = new FrameDeferredQueue<>(2);
        assertThrows(IndexOutOfBoundsException.class, () -> q.setCurrentSlot(2));
        assertThrows(IllegalArgumentException.class, () -> new FrameDeferredQueue<>(0));
    }
}
