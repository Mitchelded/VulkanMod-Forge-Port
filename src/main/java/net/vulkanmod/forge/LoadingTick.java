package net.vulkanmod.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;

/**
 * Replacement for FML's early-display periodic tick while mods load (see {@code ClientModLoaderM}).
 */
public final class LoadingTick {
    public static final Runnable POLL_EVENTS = LoadingTick::pollEvents;

    private LoadingTick() {}

    /** Mod loading may tick from resource-reload worker threads; GLFW event polling is main-thread only. */
    private static void pollEvents() {
        if (RenderSystem.isOnRenderThread())
            GLFW.glfwPollEvents();
    }
}
