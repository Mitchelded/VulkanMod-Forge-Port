package net.vulkanmod.render.util;

import org.joml.Matrix4f;

/**
 * Vulkan clip space uses depth [0, 1] whereas OpenGL uses [-1, 1].
 * <p>
 * On Fabric, VulkanMod mixed into {@code org.joml.Matrix4f} so that every {@code setOrtho/setPerspective}
 * produced a [0, 1] depth range. On Forge, JOML is loaded in the boot module layer and cannot be transformed,
 * so instead every Minecraft call site that builds a projection is redirected here (see the
 * {@code *M}/{@code *Mixin} classes referencing this helper).
 */
public final class VkProjection {
    private VkProjection() {}

    public static Matrix4f setOrtho(Matrix4f m, float left, float right, float bottom, float top, float zNear, float zFar) {
        return m.setOrtho(left, right, bottom, top, zNear, zFar, true);
    }

    public static Matrix4f setPerspective(Matrix4f m, float fovy, float aspect, float zNear, float zFar) {
        return m.setPerspective(fovy, aspect, zNear, zFar, true);
    }
}
