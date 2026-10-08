package net.vulkanmod.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.JNI.callPP;
import static org.lwjgl.system.JNI.callPPPPI;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GLFW's Vulkan entry points, called without {@code org.lwjgl.glfw.GLFWVulkan}.
 * <p>
 * On Forge, Minecraft's LWJGL jars (core, glfw, opengl...) are loaded in the boot module layer, while the Vulkan
 * bindings ({@code lwjgl-vulkan}) are bundled in this mod and live in the game layer. Boot-layer classes cannot see
 * game-layer classes, so {@code GLFWVulkan.glfwCreateWindowSurface(VkInstance, ...)} fails with
 * {@code NoClassDefFoundError: org/lwjgl/vulkan/VkInstance}. The native GLFW functions are therefore invoked
 * directly through LWJGL core's {@code JNI} trampolines, using addresses from GLFW's shared library.
 */
public final class GlfwVulkanBridge {
    private static final long CREATE_WINDOW_SURFACE = function("glfwCreateWindowSurface");
    private static final long GET_REQUIRED_INSTANCE_EXTENSIONS = function("glfwGetRequiredInstanceExtensions");

    private GlfwVulkanBridge() {}

    private static long function(String name) {
        long address = GLFW.getLibrary().getFunctionAddress(name);
        if (address == NULL)
            throw new IllegalStateException("[VULKAN] GLFW does not export " + name + "; Vulkan surface creation is impossible");
        return address;
    }

    /** {@code VkResult glfwCreateWindowSurface(VkInstance, GLFWwindow*, const VkAllocationCallbacks*, VkSurfaceKHR*)} */
    public static int glfwCreateWindowSurface(VkInstance instance, long window, LongBuffer pSurface) {
        if (pSurface.remaining() < 1)
            throw new IllegalArgumentException("pSurface must have room for one handle");
        return callPPPPI(instance.address(), window, NULL, memAddress(pSurface), CREATE_WINDOW_SURFACE);
    }

    /**
     * {@code const char** glfwGetRequiredInstanceExtensions(uint32_t* count)}; returns {@code null} if Vulkan is not
     * available to GLFW (no loader / no ICD). The returned strings are owned by GLFW.
     */
    public static PointerBuffer glfwGetRequiredInstanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            long names = callPP(memAddress(count), GET_REQUIRED_INSTANCE_EXTENSIONS);
            return memPointerBufferSafe(names, count.get(0));
        }
    }
}
