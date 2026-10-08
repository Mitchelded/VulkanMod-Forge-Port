package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.CubeMap;
import net.vulkanmod.render.util.VkProjection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Title-screen panorama projection. Replaces what Fabric's Matrix4fM did globally (see {@link VkProjection}).
 */
@Mixin(CubeMap.class)
public class CubeMapM {

    // Forge port: JOML cannot be mixed into on Forge; force Vulkan's [0,1] depth range at the call site.
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFF)Lorg/joml/Matrix4f;", remap = false))
    private Matrix4f vulkanmod$panoramaPerspective(Matrix4f matrix, float fovy, float aspect, float zNear, float zFar) {
        return VkProjection.setPerspective(matrix, fovy, aspect, zNear, zFar);
    }
}
