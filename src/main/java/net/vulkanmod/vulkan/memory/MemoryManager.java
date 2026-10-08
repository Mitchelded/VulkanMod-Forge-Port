package net.vulkanmod.vulkan.memory;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.Pair;
import net.vulkanmod.vulkan.util.VkResult;
import org.apache.commons.lang3.Validate;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import java.nio.LongBuffer;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class MemoryManager {
    private static final boolean DEBUG = false;
    public static final long BYTES_IN_MB = 1024 * 1024;

    private static MemoryManager INSTANCE;
    private static final long ALLOCATOR = Vulkan.getAllocator();

    private static final Long2ReferenceOpenHashMap<Buffer> buffers = new Long2ReferenceOpenHashMap<>();
    private static final Long2ReferenceOpenHashMap<VulkanImage> images = new Long2ReferenceOpenHashMap<>();

    static int Frames;

    private static long deviceMemory = 0;
    private static long nativeMemory = 0;

    private int currentFrame = 0;

    // Deferred destruction per frame slot (see FrameDeferredQueue for the lifetime rule).
    private final FrameDeferredQueue<Buffer.BufferInfo> freeableBuffers = new FrameDeferredQueue<>(Frames);
    private final FrameDeferredQueue<VulkanImage> freeableImages = new FrameDeferredQueue<>(Frames);
    private final FrameDeferredQueue<Runnable> frameOps = new FrameDeferredQueue<>(Frames);
    private final FrameDeferredQueue<Pair<AreaBuffer, Integer>> segmentsToFree = new FrameDeferredQueue<>(Frames);

    public static MemoryManager getInstance() {
        return INSTANCE;
    }

    public static void createInstance(int frames) {
        Frames = frames;

        INSTANCE = new MemoryManager();
    }

    MemoryManager() {
    }

    public synchronized void initFrame(int frame) {
        this.setCurrentFrame(frame);
        this.freeBuffers(frame);
        this.doFrameOps(frame);
        this.freeSegments(frame);
    }

    public void setCurrentFrame(int frame) {
        Validate.isTrue(frame < Frames, "Out of bounds frame index");
        this.currentFrame = frame;
        this.freeableBuffers.setCurrentSlot(frame);
        this.freeableImages.setCurrentSlot(frame);
        this.frameOps.setCurrentSlot(frame);
        this.segmentsToFree.setCurrentSlot(frame);
    }

    /** Shutdown path (device idle): run every pending frame op and free every pending buffer and image. */
    public synchronized void freeAllBuffers() {
        // Frame ops may defer further ops (e.g. ShaderInstance.close -> pipeline.cleanUp); run until none are left.
        for (int pass = 0; pass < 8 && this.frameOps.pending() > 0; pass++)
            this.frameOps.drainAll(Runnable::run);
        if (this.frameOps.pending() > 0)
            Initializer.LOGGER.warn("[VULKAN] {} frame ops still pending after shutdown drain", this.frameOps.pending());
        this.freeableBuffers.drainAll(info -> freeBuffer(info));
        // Fabric only freed the images of the *current* slot here, leaking images queued in the other slots.
        this.freeableImages.drainAll(VulkanImage::doFree);

//        buffers.values().forEach(buffer -> freeBuffer(buffer.getId(), buffer.getAllocation()));
//        images.values().forEach(image -> image.doFree(this));
    }

    public void createBuffer(long size, int usage, int properties, LongBuffer pBuffer, PointerBuffer pBufferMemory) {
        try (MemoryStack stack = stackPush()) {

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            allocationInfo.requiredFlags(properties);

            int result = vmaCreateBuffer(ALLOCATOR, bufferInfo, allocationInfo, pBuffer, pBufferMemory, null);
            if (result != VK_SUCCESS) {
                Initializer.LOGGER.info(String.format("Failed to create buffer with size: %.3f MB", ((float) size / BYTES_IN_MB)));
                Initializer.LOGGER.info(String.format("Tracked Device Memory used: %d/%d MB", getAllocatedDeviceMemoryMB(), getDeviceMemoryMB()));
                Initializer.LOGGER.info(getHeapStats());

                throw new RuntimeException("Failed to create buffer: %s".formatted(VkResult.decode(result)));
            }

        }
    }

    public synchronized void createBuffer(Buffer buffer, int size, int usage, int properties) {

        try (MemoryStack stack = stackPush()) {
            buffer.setBufferSize(size);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(VK_NULL_HANDLE);

            this.createBuffer(size, usage, properties, pBuffer, pAllocation);

            buffer.setId(pBuffer.get(0));
            buffer.setAllocation(pAllocation.get(0));

            if ((properties & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) > 0) {
                deviceMemory += size;
            } else {
                nativeMemory += size;
            }

            buffers.putIfAbsent(buffer.getId(), buffer);
        }
    }

    public static synchronized void createImage(int width, int height, int mipLevels, int format, int tiling, int usage, int memProperties,
                                                LongBuffer pTextureImage, PointerBuffer pTextureImageMemory) {

        try (MemoryStack stack = stackPush()) {

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.extent().width(width);
            imageInfo.extent().height(height);
            imageInfo.extent().depth(1);
            imageInfo.mipLevels(mipLevels);
            imageInfo.arrayLayers(1);
            imageInfo.format(format);
            imageInfo.tiling(tiling);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(usage);
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);
//            imageInfo.sharingMode(VK_SHARING_MODE_CONCURRENT);
            // TODO hardcoded queue family indices
            imageInfo.pQueueFamilyIndices(stack.ints(0, 1));

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            allocationInfo.requiredFlags(memProperties);

            vmaCreateImage(ALLOCATOR, imageInfo, allocationInfo, pTextureImage, pTextureImageMemory, null);

        }
    }

    public static void addImage(VulkanImage image) {
        images.putIfAbsent(image.getId(), image);
    }

    public static void MapAndCopy(long allocation, Consumer<PointerBuffer> consumer) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer data = stack.mallocPointer(1);

            vmaMapMemory(ALLOCATOR, allocation, data);
            consumer.accept(data);
            vmaUnmapMemory(ALLOCATOR, allocation);
        }
    }

    public PointerBuffer Map(long allocation) {
        PointerBuffer data = MemoryUtil.memAllocPointer(1);

        vmaMapMemory(ALLOCATOR, allocation, data);

        return data;
    }

    /**
     * Shutdown safety net (device must be idle): reports every buffer/image that no owner released and frees it, so
     * that vkDestroyDevice finds no live objects. Owners should release their resources; this logs what they missed.
     */
    public synchronized void freeLeakedResources() {
        if (!buffers.isEmpty()) {
            java.util.Map<String, long[]> byClass = new java.util.TreeMap<>();
            for (Buffer b : buffers.values()) {
                long[] acc = byClass.computeIfAbsent(b.getClass().getSimpleName(), k -> new long[2]);
                acc[0]++;
                acc[1] += b.getBufferSize();
            }
            StringBuilder sb = new StringBuilder();
            byClass.forEach((k, v) -> sb.append(k).append(" x").append(v[0]).append(" (").append(v[1] >> 10).append(" KiB) "));
            Initializer.LOGGER.info("[VULKAN] Releasing {} buffer(s) still alive at shutdown: {}", buffers.size(), sb.toString().trim());
            for (Buffer b : new java.util.ArrayList<>(buffers.values()))
                freeBuffer(b.getBufferInfo());
        }
        if (!images.isEmpty()) {
            Initializer.LOGGER.info("[VULKAN] Releasing {} image(s) still alive at shutdown", images.size());
            for (VulkanImage image : new java.util.ArrayList<>(images.values()))
                image.doFree();
        }
    }

    public static void freeBuffer(long buffer, long allocation) {
        vmaDestroyBuffer(ALLOCATOR, buffer, allocation);

        buffers.remove(buffer);
    }

    private static void freeBuffer(Buffer.BufferInfo bufferInfo) {
        vmaDestroyBuffer(ALLOCATOR, bufferInfo.id(), bufferInfo.allocation());

        if (bufferInfo.type() == MemoryType.Type.DEVICE_LOCAL) {
            deviceMemory -= bufferInfo.bufferSize();
        } else {
            nativeMemory -= bufferInfo.bufferSize();
        }

        buffers.remove(bufferInfo.id());
    }

    public static void freeImage(long image, long allocation) {
        vmaDestroyImage(ALLOCATOR, image, allocation);

        images.remove(image);
    }

    public synchronized void addToFreeable(Buffer buffer) {
        Buffer.BufferInfo bufferInfo = buffer.getBufferInfo();

        checkBuffer(bufferInfo);

        freeableBuffers.defer(bufferInfo);
    }

    public synchronized void addToFreeable(VulkanImage image) {
        freeableImages.defer(image);
    }

    public synchronized void addFrameOp(Runnable runnable) {
        this.frameOps.defer(runnable);
    }

    public synchronized void doFrameOps(int frame) {
        this.frameOps.drain(frame, Runnable::run);
    }

    private void freeBuffers(int frame) {
        freeableBuffers.drain(frame, info -> freeBuffer(info));
        freeableImages.drain(frame, VulkanImage::doFree);
    }

    private void checkBuffer(Buffer.BufferInfo bufferInfo) {
        if (buffers.get(bufferInfo.id()) == null) {
            throw new RuntimeException("trying to free not present buffer");
        }
    }

    private void freeSegments(int frame) {
        this.segmentsToFree.drain(frame, pair -> pair.first.setSegmentFree(pair.second));
    }

    public synchronized void addToFreeSegment(AreaBuffer areaBuffer, int offset) {
        this.segmentsToFree.defer(new Pair<>(areaBuffer, offset));
    }

    public int getNativeMemoryMB() {
        return bytesInMb(nativeMemory);
    }

    public int getAllocatedDeviceMemoryMB() {
        return bytesInMb(deviceMemory);
    }

    public int getDeviceMemoryMB() {
        return bytesInMb(MemoryTypes.GPU_MEM.vkMemoryHeap.size());
    }

    int bytesInMb(long bytes) {
        return (int) (bytes / BYTES_IN_MB);
    }

    public String getHeapStats() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaBudget.Buffer vmaBudgets = VmaBudget.calloc(DeviceManager.memoryProperties.memoryHeapCount(), stack);

            vmaGetHeapBudgets(ALLOCATOR, vmaBudgets);

            VmaBudget vmaBudget = vmaBudgets.get(MemoryTypes.GPU_MEM.vkMemoryType.heapIndex());
            long usage = vmaBudget.usage();
            long budget = vmaBudget.budget();

            return String.format("Device Memory Heap Usage: %d/%dMB", bytesInMb(usage), bytesInMb(budget));
        }
    }
}
