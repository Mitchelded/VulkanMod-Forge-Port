package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.ShaderMixed;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.parser.GlslConverter;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

@Mixin(ShaderInstance.class)
public class ShaderInstanceM implements ShaderMixed {

    @Shadow @Final private Map<String, com.mojang.blaze3d.shaders.Uniform> uniformMap;
    @Shadow @Final private String name;

    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform MODEL_VIEW_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform PROJECTION_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform INVERSE_VIEW_ROTATION_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform COLOR_MODULATOR;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform LINE_WIDTH;

    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform GLINT_ALPHA;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_START;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_END;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_COLOR;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_SHAPE;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform TEXTURE_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform GAME_TIME;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform SCREEN_SIZE;

    private String vsPath;
    private String fsName;

    private GraphicsPipeline pipeline;
    boolean isLegacy = false;


    public GraphicsPipeline getPipeline() {
        return pipeline;
    }

    /*
     * Forge port: Forge adds ShaderInstance(ResourceProvider, ResourceLocation, VertexFormat) and turns the vanilla
     * (ResourceProvider, String, VertexFormat) constructor into a delegating one. All shader loading happens in the
     * ResourceLocation constructor, so every constructor injection below targets it explicitly (a bare "<init>"
     * selector would bind to the first, delegating, constructor only).
     */
    private static final String FORGE_CTOR = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V";

    @Inject(method = FORGE_CTOR, at = @At("RETURN"))
    private void create(ResourceProvider resourceProvider, ResourceLocation shaderLocation, VertexFormat format, CallbackInfo ci) {
        // Same value the Fabric constructor received as its "name" parameter (path for minecraft:, else "ns:path").
        String name = this.name;

        try {
            if (Pipeline.class.getResourceAsStream(String.format("/assets/vulkanmod/shaders/minecraft/core/%s/%s.json", name, name)) == null) {
                createLegacyShader(resourceProvider, format);
                return;
            }

            String path = String.format("minecraft/core/%s/%s", name, name);
            Pipeline.Builder pipelineBuilder = new Pipeline.Builder(format, path);
            pipelineBuilder.parseBindingsJSON();
            pipelineBuilder.compileShaders();
            this.pipeline = pipelineBuilder.createGraphicsPipeline();
        } catch (Exception e) {
            System.out.printf("Error on shader %s creation\n", name);
            e.printStackTrace();
            throw e;
        }
    }

    @Redirect(method = FORGE_CTOR, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;"))
    private Program loadNames(ResourceProvider resourceProvider, Program.Type type, String name) {
        if (this.name.contains(String.valueOf(ResourceLocation.NAMESPACE_SEPARATOR))) {
            ResourceLocation location = ResourceLocation.tryParse(name);
            String path = location.withPath("shaders/core/%s".formatted(location.getPath())).toString();

            switch (type) {
                case VERTEX -> this.vsPath = path;
                case FRAGMENT -> this.fsName = path;
            }
        }

        return null;
    }

    // require = 0: Oculus/Iris also @Redirects this same call site. When both run with equal
    // priority Mixin picks one and skips the other with only a WARN; the skipped @Redirect
    // is counted as 0 applied, which under defaultRequire=1 becomes FATAL. Marking us
    // non-required lets Mixin accept the skip cleanly. Correctness is preserved by the
    // no-op _glBindAttribLocation stub in GlStateManagerM: whichever redirect ends up
    // winning, its forwarding call to Uniform.glBindAttribLocation lands on that stub
    // under Vulkan, so no GL call is ever issued.
    @Redirect(method = FORGE_CTOR, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/Uniform;glBindAttribLocation(IILjava/lang/CharSequence;)V"), require = 0)
    private void bindAttr(int program, int index, CharSequence name) {}

    /*
     * The three ShaderInstance lifecycle methods (close/apply/clear) are injected at HEAD and
     * cancelled instead of @Overwrite'd. @Overwrite replaces the method body wholesale, which
     * deletes any INVOKE instruction other mods (Embeddium, Oculus/Iris) @Redirect inside. Using
     * @Inject(HEAD, cancellable=true) keeps vanilla's bytecode intact so those mixins can still
     * apply; at runtime we cancel before vanilla runs, so behaviour matches the previous overwrite
     * exactly - the vanilla GL path and any redirects wrapping it never execute under Vulkan.
     *
     * Keep the injector method names stable (prefix `vulkan$`) so they don't collide with other
     * mods' handler names in the merged class.
     */

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void vulkan$close(CallbackInfo ci) {
        if (this.pipeline != null)
            this.pipeline.cleanUp();
        ci.cancel();
    }

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void vulkan$apply(CallbackInfo ci) {
        if (this.isLegacy) {
            if (this.MODEL_VIEW_MATRIX != null) {
                this.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
            }

            if (this.PROJECTION_MATRIX != null) {
                this.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            }

            if (this.INVERSE_VIEW_ROTATION_MATRIX != null) {
                this.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
            }

            if (this.COLOR_MODULATOR != null) {
                this.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
            }

            if (this.GLINT_ALPHA != null) {
                this.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
            }

            if (this.FOG_START != null) {
                this.FOG_START.set(RenderSystem.getShaderFogStart());
            }

            if (this.FOG_END != null) {
                this.FOG_END.set(RenderSystem.getShaderFogEnd());
            }

            if (this.FOG_COLOR != null) {
                this.FOG_COLOR.set(RenderSystem.getShaderFogColor());
            }

            if (this.FOG_SHAPE != null) {
                this.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
            }

            if (this.TEXTURE_MATRIX != null) {
                this.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
            }

            if (this.GAME_TIME != null) {
                this.GAME_TIME.set(RenderSystem.getShaderGameTime());
            }

            if (this.SCREEN_SIZE != null) {
                Window window = Minecraft.getInstance().getWindow();
                this.SCREEN_SIZE.set((float)window.getWidth(), (float)window.getHeight());
            }

            if (this.LINE_WIDTH != null) {
                this.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());
            }
        }
        ci.cancel();
    }

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
    private void vulkan$clear(CallbackInfo ci) {
        ci.cancel();
    }

    private void setUniformSuppliers(UBO ubo) {

        for(Uniform vUniform : ubo.getUniforms()) {
            com.mojang.blaze3d.shaders.Uniform uniform = this.uniformMap.get(vUniform.getName());

            if(uniform == null) {
                Initializer.LOGGER.error(String.format("Error: field %s not present in uniform map", vUniform.getName()));
                continue;
            }

            Supplier<MappedBuffer> supplier;
            ByteBuffer byteBuffer;

            if (uniform.getType() <= 3) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getIntBuffer());
            } else if (uniform.getType() <= 10) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getFloatBuffer());
            } else {
                throw new RuntimeException("out of bounds value for uniform " + uniform);
            }


            MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
            supplier = () -> mappedBuffer;

            vUniform.setSupplier(supplier);
        }

    }

    private void createLegacyShader(ResourceProvider resourceProvider, VertexFormat format) {
        try {
            String vertPath = this.vsPath + ".vsh";
            Resource resource = resourceProvider.getResourceOrThrow(ResourceLocation.tryParse(vertPath));
            InputStream inputStream = resource.open();
            String vshSrc = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

            String fragPath = this.fsName + ".fsh";
            resource = resourceProvider.getResourceOrThrow(ResourceLocation.tryParse(fragPath));
            inputStream = resource.open();
            String fshSrc = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

            GlslConverter converter = new GlslConverter();
            Pipeline.Builder builder = new Pipeline.Builder(format, this.name);

            converter.process(vshSrc, fshSrc);
            UBO ubo = converter.getUBO();
            this.setUniformSuppliers(ubo);

            builder.setUniforms(Collections.singletonList(ubo), converter.getSamplerList());
            builder.compileShaders(this.name, converter.getVshConverted(), converter.getFshConverted());

            this.pipeline = builder.createGraphicsPipeline();
            this.isLegacy = true;

        } catch (Exception e) {
            Initializer.LOGGER.error("Error on shader {} conversion/compilation", this.name);
            e.printStackTrace();
        }
    }
}

