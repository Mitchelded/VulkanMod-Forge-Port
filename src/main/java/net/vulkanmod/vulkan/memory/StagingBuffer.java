package net.vulkanmod.vulkan.memory;

import net.vulkanmod.render.chunk.util.Util;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.libc.LibCString.nmemcpy;
import static org.lwjgl.vulkan.VK10.*;

public class StagingBuffer extends Buffer {
    private static final long MAX_GROWTH_SIZE = 256L * 1024 * 1024;

    public StagingBuffer(int bufferSize) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.usedBytes = 0;
        this.offset = 0;

        this.createBuffer(bufferSize);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {

        if(size > this.bufferSize - this.usedBytes) {
            resizeBuffer(size);
        }

//        VUtil.memcpy(byteBuffer, this.data.getByteBuffer(0, this.bufferSize), this.usedBytes);
        nmemcpy(this.data.get(0) + this.usedBytes, MemoryUtil.memAddress(byteBuffer), size);

        offset = usedBytes;
        usedBytes += size;

        //createVertexBuffer(vertexSize, vertexCount, byteBuffer);
    }

    public void align(int alignment) {
        int alignedValue = Util.align(usedBytes, alignment);

        if(alignedValue > this.bufferSize) {
            resizeBuffer(0);
            return;
        }

        usedBytes = alignedValue;
    }

    /**
     * Replaces a full buffer. The old one is freed only after the in-flight frames that may still copy from it
     * complete (deferred free), so the replacement is filled from offset 0. Previously the old usedBytes carried over
     * and the size doubled each time, reaching 2 GiB and overflowing int during large modpack texture loads.
     */
    private void resizeBuffer(int minSize) {
        // Grow geometrically while small (avoids replacing the buffer many times per frame under heavy chunk
        // uploads), but never beyond what a single request needs once past the cap
        long newSize = Math.max(Math.min((long) this.bufferSize * 2, MAX_GROWTH_SIZE), Math.max((long) this.bufferSize, minSize));
        if (newSize > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Staging upload too large: " + minSize);

        MemoryManager.getInstance().addToFreeable(this);
        this.createBuffer((int) newSize);
        this.usedBytes = 0;
        this.offset = 0;

        System.out.println("replaced staging buffer, size: " + newSize);
    }
}
