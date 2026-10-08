package net.vulkanmod.vulkan.queue;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import net.vulkanmod.vulkan.select.QueueFamilySelector;
import net.vulkanmod.vulkan.select.SelectionException;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

public abstract class Queue {
    private static VkDevice DEVICE;

    private static QueueFamilyIndices queueFamilyIndices;
    protected CommandPool commandPool;

    private final VkQueue queue;

    public synchronized CommandPool.CommandBuffer beginCommands() {
        return this.commandPool.beginCommands();
    }

    Queue(MemoryStack stack, int familyIndex) {
        this(stack, familyIndex, true);
    }

    Queue(MemoryStack stack, int familyIndex, boolean initCommandPool) {
        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(DeviceManager.vkDevice, familyIndex, 0, pQueue);
        this.queue = new VkQueue(pQueue.get(0), DeviceManager.vkDevice);

        if (initCommandPool)
            this.commandPool = new CommandPool(familyIndex);
    }

    public synchronized long submitCommands(CommandPool.CommandBuffer commandBuffer) {
        return this.commandPool.submitCommands(commandBuffer, queue);
    }

    public VkQueue queue() {
        return this.queue;
    }

    public void cleanUp() {
        if (commandPool != null)
            commandPool.cleanUp();
    }

    public void waitIdle() {
        vkQueueWaitIdle(queue);
    }

    public enum Family {
        Graphics,
        Transfer,
        Compute
    }

    public static QueueFamilyIndices getQueueFamilies() {
        if (DEVICE == null)
            DEVICE = Vulkan.getVkDevice();

        if (queueFamilyIndices == null) {
            queueFamilyIndices = findQueueFamilies(DEVICE.getPhysicalDevice());
        }
        return queueFamilyIndices;
    }

    /**
     * Queries the device's queue families and the surface present support, then delegates the policy to
     * {@link QueueFamilySelector} (pure, unit tested).
     *
     * @throws SelectionException if the device lacks a graphics, present or compute capable family
     */
    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer queueFamilyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);
            List<QueueFamilySelector.Family> families = new ArrayList<>(queueFamilies.capacity());
            for (int i = 0; i < queueFamilies.capacity(); i++) {
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, Vulkan.getSurface(), presentSupport);
                families.add(new QueueFamilySelector.Family(queueFamilies.get(i).queueFlags(),
                        queueFamilies.get(i).queueCount(), presentSupport.get(0) == VK_TRUE));
            }

            QueueFamilySelector.Selection selection = QueueFamilySelector.select(families);

            QueueFamilyIndices indices = new QueueFamilyIndices();
            indices.graphicsFamily = selection.graphics();
            indices.presentFamily = selection.present();
            indices.computeFamily = selection.compute();
            indices.transferFamily = selection.transfer();
            return indices;
        }
    }

    public static class QueueFamilyIndices {
        public int graphicsFamily = VK_QUEUE_FAMILY_IGNORED;
        public int presentFamily = VK_QUEUE_FAMILY_IGNORED;
        public int transferFamily = VK_QUEUE_FAMILY_IGNORED;
        public int computeFamily = VK_QUEUE_FAMILY_IGNORED;

        public boolean isComplete() {
            return graphicsFamily != -1 && presentFamily != -1 && transferFamily != -1 && computeFamily != -1;
        }

        public boolean isSuitable() {
            return graphicsFamily != -1 && presentFamily != -1;
        }

        public int[] unique() {
            return IntStream.of(graphicsFamily, presentFamily, transferFamily, computeFamily).distinct().toArray();
        }

        public int[] array() {
            return new int[]{graphicsFamily, presentFamily};
        }
    }
}
