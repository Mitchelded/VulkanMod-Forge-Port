package net.vulkanmod.vulkan;

/**
 * Thrown when the Vulkan renderer cannot start. The message is written for players: Minecraft puts it at the top
 * of the crash report, and the full technical dump is in the log just before it ({@code [VULKAN]} lines).
 * <p>
 * There is no OpenGL fallback: VulkanMod's mixins replace OpenGL state handling throughout the client, and the window
 * was created without an OpenGL context. Remove the mod to play with OpenGL.
 */
public class VulkanInitException extends RuntimeException {
    public VulkanInitException(Throwable cause) {
        super(buildMessage(cause), cause);
    }

    private static String buildMessage(Throwable cause) {
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return "VulkanMod could not initialise Vulkan: " + detail + ". "
                + "Make sure your GPU supports Vulkan 1.2 and your graphics driver is up to date. "
                + "See the [VULKAN] lines in logs/latest.log for the detected loader, GPUs, drivers and extensions. "
                + "Remove VulkanMod from the mods folder to play with the OpenGL renderer.";
    }
}
