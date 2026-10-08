package net.vulkanmod.vulkan.select;

/**
 * A capability requirement that the Vulkan implementation does not meet. The message is meant for users.
 */
public class SelectionException extends RuntimeException {
    public SelectionException(String message) {
        super(message);
    }
}
