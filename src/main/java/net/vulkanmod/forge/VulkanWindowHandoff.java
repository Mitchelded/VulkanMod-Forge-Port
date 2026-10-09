package net.vulkanmod.forge;

import net.minecraftforge.fml.loading.ImmediateWindowHandler;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Obtains a GLFW window that Vulkan can present to.
 * <p>
 * Vanilla (and Fabric) create the window in {@code Window.<init>} with {@code glfwCreateWindow}; VulkanMod on Fabric
 * simply set {@code GLFW_CLIENT_API = GLFW_NO_API} right before that call. Forge 1.20.1 replaces that call with
 * {@link ImmediateWindowHandler#setupMinecraftWindow}, which, when FML's early loading screen is enabled
 * ({@code earlyWindowControl = true} in {@code config/fml.toml}, the default) returns the <b>already existing</b>
 * early-display window. That window owns an OpenGL context, and a window with a client-API context cannot be used to
 * create a {@code VkSurfaceKHR} ({@code glfwCreateWindowSurface} fails with {@code GLFW_INVALID_VALUE}).
 * <p>
 * Strategy:
 * <ol>
 *   <li>Set the Vulkan window hints, then let FML perform its normal handoff. With the early display disabled, FML
 *   creates the window with the current hints, i.e. directly as a {@code GLFW_NO_API} window.</li>
 *   <li>If what comes back has an OpenGL context (early display enabled), remember its geometry, destroy it (which
 *   also destroys the GL context and every GL object the early display owned) and create a {@code GLFW_NO_API}
 *   replacement at the same position and size.</li>
 * </ol>
 * The early display's own render thread is stopped by FML during the handoff and only a lazily created
 * {@code ForgeLoadingOverlay} would draw into the old window again; that overlay is replaced in {@code MinecraftMixin}.
 */
public final class VulkanWindowHandoff {

    private VulkanWindowHandoff() {}

    public static long createWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        applyVulkanWindowHints();

        long handle = ImmediateWindowHandler.setupMinecraftWindow(width, height, title, monitor);
        if (handle == 0L) {
            throw new IllegalStateException("[VULKAN] Forge failed to provide a GLFW window");
        }

        int clientApi = glfwGetWindowAttrib(handle, GLFW_CLIENT_API);
        if (clientApi == GLFW_NO_API) {
            Initializer.LOGGER.info("[VULKAN] Window created without a client API context (FML early display disabled)");
            return handle;
        }

        Initializer.LOGGER.info("[VULKAN] FML early display handed over an OpenGL window (client API 0x{}); replacing it with a Vulkan-compatible window",
                Integer.toHexString(clientApi));
        return replaceWindow(handle, title.get(), monitor.getAsLong());
    }

    /**
     * Post-handoff variant: called from an @Inject at Window.<init> RETURN when we did <em>not</em> replace the
     * ImmediateWindowHandler.setupMinecraftWindow call site (because another mod — e.g. Embeddium — also @Redirects it,
     * and sharing the call site via @Inject + late swap avoids the mixin conflict). Takes the window handle vanilla's
     * ctor assigned to {@code Window.window} and, if it owns a GL context, destroys it and returns a GLFW_NO_API
     * replacement at the same geometry. Otherwise returns the input unchanged.
     */
    public static long handoffExistingWindow(long existingHandle, String title) {
        if (existingHandle == 0L) {
            throw new IllegalStateException("[VULKAN] Window handoff called with a null handle");
        }
        int clientApi = glfwGetWindowAttrib(existingHandle, GLFW_CLIENT_API);
        if (clientApi == GLFW_NO_API) {
            Initializer.LOGGER.info("[VULKAN] Window already Vulkan-compatible (client API none)");
            return existingHandle;
        }
        Initializer.LOGGER.info("[VULKAN] Vanilla window handoff produced an OpenGL window (client API 0x{}); replacing it with a Vulkan-compatible window",
                Integer.toHexString(clientApi));
        return replaceWindow(existingHandle, title, 0L);
    }

    public static void applyVulkanWindowHintsExternal() {
        applyVulkanWindowHints();
    }

    private static void applyVulkanWindowHints() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        // Fix Gnome Client-Side Decorators (same as Fabric VulkanMod)
        boolean noDecorations = (Platform.isGnome() | Platform.isWeston() | Platform.isGeneric()) && Platform.isWayLand();
        glfwWindowHint(GLFW_DECORATED, noDecorations ? GLFW_FALSE : GLFW_TRUE);
    }

    private static long replaceWindow(long glWindow, String title, long monitor) {
        int x, y, w, h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1), py = stack.mallocInt(1);
            glfwGetWindowPos(glWindow, px, py);
            x = px.get(0);
            y = py.get(0);
            glfwGetWindowSize(glWindow, px, py);
            w = Math.max(1, px.get(0));
            h = Math.max(1, py.get(0));
        }
        boolean maximized = glfwGetWindowAttrib(glWindow, GLFW_MAXIMIZED) == GLFW_TRUE;

        // FML made the early context current on this thread during the handoff; release it before destroying.
        glfwMakeContextCurrent(0L);
        glfwDestroyWindow(glWindow);

        applyVulkanWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(w, h, title, monitor, 0L);
        if (window == 0L) {
            throw new IllegalStateException("[VULKAN] glfwCreateWindow failed while replacing the FML early-display window: " + lastGlfwError());
        }

        if (monitor == 0L) {
            glfwSetWindowPos(window, x, y);
            if (maximized)
                glfwMaximizeWindow(window);
        }
        glfwShowWindow(window);

        Initializer.LOGGER.info("[VULKAN] Replacement window {}x{} at ({}, {}){}", w, h, x, y, maximized ? " maximized" : "");
        return window;
    }

    private static String lastGlfwError() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var description = stack.mallocPointer(1);
            int code = GLFW.glfwGetError(description);
            String text = description.get(0) != 0L ? description.getStringUTF8(0) : "no description";
            return "GLFW error 0x" + Integer.toHexString(code) + ": " + text;
        }
    }
}
