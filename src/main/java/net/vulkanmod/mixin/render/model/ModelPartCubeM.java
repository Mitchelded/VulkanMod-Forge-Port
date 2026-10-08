package net.vulkanmod.mixin.render.model;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.vulkanmod.interfaces.ModelPartCubeMixed;
import net.vulkanmod.render.model.CubeModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ModelPart.Cube.class)
public class ModelPartCubeM implements ModelPartCubeMixed {

    CubeModel cube;

    // Forge ships upstream Mixin 0.8.5, which only allows @Inject into constructors at RETURN (Fabric's Mixin fork
    // also allowed the original FIELD injection point). The handler only reads constructor arguments, so RETURN is
    // equivalent.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void getVertices(int i, int j, float f, float g, float h, float k, float l, float m, float n, float o, float p, boolean bl, float q, float r, Set<Direction> set, CallbackInfo ci) {
        //TODO check if set is needed
        CubeModel cube = new CubeModel();
        cube.setVertices(i, j, f, g, h, k, l, m, n, o, p, bl, q, r, set);
        this.cube = cube;
    }


    @Override
    public CubeModel getCubeModel() {
        return this.cube;
    }
}
