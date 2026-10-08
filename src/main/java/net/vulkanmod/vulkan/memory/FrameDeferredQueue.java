package net.vulkanmod.vulkan.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-frame-slot queue of work that must wait until the GPU no longer uses a resource.
 * <p>
 * Resource lifetime rule used by the renderer: anything released while recording frame slot {@code f} may still be
 * referenced by command buffers of that slot. The slot's in-flight fence is waited on before the slot is reused, at
 * which point {@link #drain(int, Consumer)} hands back everything that was deferred during the previous use of that
 * slot. At shutdown (after {@code vkDeviceWaitIdle}) {@link #drainAll(Consumer)} releases everything.
 * <p>
 * Not thread-safe; the owner synchronises.
 */
public final class FrameDeferredQueue<T> {
    private final List<List<T>> slots;
    private int currentSlot;

    public FrameDeferredQueue(int frameSlots) {
        if (frameSlots < 1)
            throw new IllegalArgumentException("frameSlots must be >= 1");
        this.slots = new ArrayList<>(frameSlots);
        for (int i = 0; i < frameSlots; i++)
            this.slots.add(new ArrayList<>());
    }

    public int slotCount() {
        return slots.size();
    }

    /** Selects the slot that subsequent {@link #defer} calls belong to (the frame currently being recorded). */
    public void setCurrentSlot(int slot) {
        if (slot < 0 || slot >= slots.size())
            throw new IndexOutOfBoundsException("frame slot " + slot + " of " + slots.size());
        this.currentSlot = slot;
    }

    public void defer(T item) {
        slots.get(currentSlot).add(item);
    }

    /** Releases everything deferred into {@code slot}; call only after that slot's fence has signalled. */
    public void drain(int slot, Consumer<? super T> release) {
        List<T> list = slots.get(slot);
        // Copy first: releasing may defer new work (e.g. a frame op that frees a buffer) into the same slot.
        List<T> items = new ArrayList<>(list);
        list.clear();
        for (T item : items)
            release.accept(item);
    }

    /** Releases everything in every slot (device must be idle). */
    public void drainAll(Consumer<? super T> release) {
        for (int i = 0; i < slots.size(); i++)
            drain(i, release);
    }

    public int pending() {
        int n = 0;
        for (List<T> l : slots)
            n += l.size();
        return n;
    }

    public int pending(int slot) {
        return slots.get(slot).size();
    }
}
