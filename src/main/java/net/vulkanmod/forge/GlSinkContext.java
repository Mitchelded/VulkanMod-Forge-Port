package net.vulkanmod.forge;

import net.vulkanmod.Initializer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Hidden OpenGL context made current on the render thread.
 * <p>
 * VulkanMod replaces Minecraft's renderer, so no OpenGL context normally exists. Mods that call LWJGL's OpenGL
 * bindings directly (LDLib shaders, Flywheel, map mods, ...) then jump through null function pointers and crash the
 * JVM with EXCEPTION_ACCESS_VIOLATION. This 1x1 invisible context absorbs those calls: they execute against a real
 * driver context, nothing they draw reaches the Vulkan swapchain. Disable with {@code -Dvulkanmod.glSink=false}.
 */
public final class GlSinkContext {
    private static long window;

    private GlSinkContext() {}

    public static void create() {
        if (window != 0L || !Boolean.parseBoolean(System.getProperty("vulkanmod.glSink", "true")))
            return;

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        window = glfwCreateWindow(1, 1, "VulkanMod GL sink", 0L, 0L);
        glfwDefaultWindowHints();

        if (window == 0L) {
            Initializer.LOGGER.warn("[VULKAN] Could not create the hidden OpenGL sink context; mods calling OpenGL directly will crash");
            return;
        }

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        Initializer.LOGGER.info("[VULKAN] Hidden OpenGL sink context current on {}: {} / {}",
                Thread.currentThread().getName(), GL11C.glGetString(GL11C.GL_RENDERER), GL11C.glGetString(GL11C.GL_VERSION));
    }

    public static void destroy() {
        if (window == 0L)
            return;
        glfwMakeContextCurrent(0L);
        glfwDestroyWindow(window);
        window = 0L;
    }
}
