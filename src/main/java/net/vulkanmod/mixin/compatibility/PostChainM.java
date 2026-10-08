package net.vulkanmod.mixin.compatibility;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.vulkanmod.render.util.VkProjection;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(PostChain.class)
public abstract class PostChainM {

    @Shadow @Final private List<PostPass> passes;

    @Shadow private float lastStamp;
    @Shadow private float time;

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void process(float f) {
        if (f < this.lastStamp) {
            this.time += 1.0F - this.lastStamp;
            this.time += f;
        } else {
            this.time += f - this.lastStamp;
        }

        this.lastStamp = f;

        while (this.time > 20.0F) {
            this.time -= 20.0F;
        }

        for (PostPass postPass : this.passes) {
            postPass.process(this.time / 20.0F);
        }

        Renderer.resetViewport();
    }

    // Forge port: JOML cannot be mixed into on Forge; force Vulkan's [0,1] depth range at the call site.
    @Redirect(method = "updateOrthoMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setOrtho(FFFFFF)Lorg/joml/Matrix4f;", remap = false))
    private Matrix4f vulkanmod$postOrtho(Matrix4f matrix, float left, float right, float bottom, float top, float zNear, float zFar) {
        return VkProjection.setOrtho(matrix, left, right, bottom, top, zNear, zFar);
    }
}
