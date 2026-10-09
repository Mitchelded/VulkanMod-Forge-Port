package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.SortedSet;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    @Final
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;
    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    public abstract void graphicsChanged();

    @Shadow
    private int lastViewDistance;
    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    @Final
    private Set<BlockEntity> globalBlockEntities;
    @Shadow
    private boolean generateClouds;
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    protected abstract boolean shouldShowEntityOutlines();

    @Shadow
    public abstract void needsUpdate();

    @Shadow
    private int ticks;

    // Added by Forge's patch (no SRG name), hence remap = false.
    @Shadow(remap = false)
    public abstract Frustum getFrustum();

    @Shadow
    public abstract void renderLevel(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f);

    private WorldRenderer worldRenderer;

    @Unique
    private Object2ReferenceOpenHashMap<Class<? extends Entity>, ObjectArrayList<Pair<Entity, MultiBufferSource>>> entitiesMap = new Object2ReferenceOpenHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(Minecraft minecraft, EntityRenderDispatcher entityRenderDispatcher, BlockEntityRenderDispatcher blockEntityRenderDispatcher, RenderBuffers renderBuffers, CallbackInfo ci) {
        this.worldRenderer = WorldRenderer.init(this.renderBuffers);
    }

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void setLevel(ClientLevel clientLevel, CallbackInfo ci) {
        this.worldRenderer.setLevel(clientLevel);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void allChanged(CallbackInfo ci) {
        this.worldRenderer.allChanged();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V", ordinal = 1, shift = At.Shift.BEFORE))
    private void renderBlockEntities(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
        Vec3 pos = camera.getPosition();
        this.worldRenderer.renderBlockEntities(poseStack, pos.x(), pos.y(), pos.z(), this.destructionProgress, f);
    }

    /*
     * All chunk-renderer entry points below are @Inject(HEAD,cancellable=true)+ci.cancel() instead of @Overwrite
     * so other rendering mods (Embeddium's core.render.world.WorldRendererMixin, Oculus) can still @Redirect/@Inject
     * inside the vanilla bytecode. Runtime behaviour matches the previous @Overwrite exactly - VulkanMod's logic
     * runs then cancels, so vanilla's body (and any redirects wrapping it) never executes.
     */

    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void vulkan$setupRender(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator, CallbackInfo ci) {
        this.worldRenderer.setupRenderer(camera, frustum, isCapturedFrustum, spectator);
        entitiesMap.clear();
        ci.cancel();
    }

    @Inject(method = "compileChunks", at = @At("HEAD"), cancellable = true)
    private void vulkan$compileChunks(Camera camera, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "isChunkCompiled", at = @At("HEAD"), cancellable = true)
    private void vulkan$isChunkCompiled(BlockPos blockPos, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.worldRenderer.isSectionCompiled(blockPos));
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void vulkan$renderChunkLayer(RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo ci) {
        this.worldRenderer.renderSectionLayer(renderType, poseStack, camX, camY, camZ, projectionMatrix);

        // Forge patches this method to fire RenderLevelStageEvent (AFTER_SOLID_BLOCKS, ...); keep that contract.
        renderType.setupRenderState();
        ForgeHooksClient.dispatchRenderStage(renderType, (LevelRenderer) (Object) this, poseStack, projectionMatrix, this.ticks,
                this.minecraft.gameRenderer.getMainCamera(), this.getFrustum());
        renderType.clearRenderState();
        ci.cancel();
    }

    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void vulkan$allChanged(CallbackInfo ci) {
        if (this.level != null) {
            this.graphicsChanged();
            this.level.clearTintCaches();

            this.generateClouds = true;
            ItemBlockRenderTypes.setFancy(Minecraft.useFancyGraphics());
            this.lastViewDistance = this.minecraft.options.getEffectiveRenderDistance();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }
        }
        ci.cancel();
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void vulkan$setSectionDirty(int x, int y, int z, boolean flag, CallbackInfo ci) {
        this.worldRenderer.setSectionDirty(x, y, z, flag);
        ci.cancel();
    }

    @Inject(method = "getChunkStatistics", at = @At("HEAD"), cancellable = true)
    private void vulkan$getChunkStatistics(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(this.worldRenderer.getChunkStatistics());
    }

    @Inject(method = "hasRenderedAllChunks", at = @At("HEAD"), cancellable = true)
    private void vulkan$hasRenderedAllChunks(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.worldRenderer.graphNeedsUpdate());
    }

    @Inject(method = "countRenderedChunks", at = @At("HEAD"), cancellable = true)
    private void vulkan$countRenderedChunks(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.worldRenderer.getVisibleSectionsCount());
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void vulkan$renderEntity(Entity entity, double d, double e, double f, float g, PoseStack poseStack, MultiBufferSource multiBufferSource, CallbackInfo ci) {
        if (!Initializer.CONFIG.entityCulling) {
            double h = Mth.lerp(g, entity.xOld, entity.getX());
            double i = Mth.lerp(g, entity.yOld, entity.getY());
            double j = Mth.lerp(g, entity.zOld, entity.getZ());
            float k = Mth.lerp(g, entity.yRotO, entity.getYRot());
            this.entityRenderDispatcher.render(entity, h - d, i - e, j - f, k, g, poseStack, multiBufferSource, this.entityRenderDispatcher.getPackedLightCoords(entity, g));
            ci.cancel();
            return;
        }

        var entityClass = entity.getClass();
        var list = this.entitiesMap.get(entityClass);

        if (list == null) {
            list = new ObjectArrayList<>();
            this.entitiesMap.put(entityClass, list);
        }

        list.add(new Pair<>(entity, multiBufferSource));
        ci.cancel();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V",
            shift = At.Shift.AFTER, ordinal = 0)
    )
    private void renderEntities(PoseStack poseStack, float partialTicks, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
        if (!Initializer.CONFIG.entityCulling)
            return;

        Vec3 cameraPos = WorldRenderer.getCameraPos();

        for (var list : this.entitiesMap.values()) {
            for (var pair : list) {
                Entity entity = pair.first;
                MultiBufferSource multiBufferSource = pair.second;

                double h = Mth.lerp(partialTicks, entity.xOld, entity.getX());
                double i = Mth.lerp(partialTicks, entity.yOld, entity.getY());
                double j = Mth.lerp(partialTicks, entity.zOld, entity.getZ());
                float k = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
                this.entityRenderDispatcher.render(entity, h - cameraPos.x, i - cameraPos.y, j - cameraPos.z, k, partialTicks, poseStack, multiBufferSource, this.entityRenderDispatcher.getPackedLightCoords(entity, partialTicks));
            }
        }
    }

    @Redirect(method = "renderWorldBorder", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getDepthFar()F"))
    private float getRenderDistanceZFar(GameRenderer instance) {
        return instance.getRenderDistance() * 4F;
    }

//    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
//    private Iterable<Entity> replaceIterator(ClientLevel instance) {
//
//        return () -> new Iterator<Entity>() {
//            @Override
//            public boolean hasNext() {
//                return false;
//            }
//
//            @Override
//            public Entity next() {
//                return null;
//            }
//        };
//    }
//
//    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
//            target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;",
//            shift = At.Shift.AFTER),
//            locals = LocalCapture.CAPTURE_FAILHARD
//    )
//    private void renderEntities(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
//        for(Entity entity : this.level.entitiesForRendering()) {
//            if (this.entityRenderDispatcher.shouldRender(entity, frustum, d0, d1, d2) || entity.hasIndirectPassenger(this.minecraft.player)) {
//                BlockPos blockpos = entity.blockPosition();
//                if ((this.level.isOutsideBuildHeight(blockpos.getY()) || this.isChunkCompiled(blockpos)) && (entity != p_109604_.getEntity() || p_109604_.isDetached() || p_109604_.getEntity() instanceof LivingEntity && ((LivingEntity)p_109604_.getEntity()).isSleeping()) && (!(entity instanceof LocalPlayer) || p_109604_.getEntity() == entity)) {
//                    ++this.renderedEntities;
//                    if (entity.tickCount == 0) {
//                        entity.xOld = entity.getX();
//                        entity.yOld = entity.getY();
//                        entity.zOld = entity.getZ();
//                    }
//
//                    MultiBufferSource multibuffersource;
//                    if (this.shouldShowEntityOutlines() && this.minecraft.shouldEntityAppearGlowing(entity)) {
//                        flag3 = true;
//                        OutlineBufferSource outlinebuffersource = this.renderBuffers.outlineBufferSource();
//                        multibuffersource = outlinebuffersource;
//                        int i = entity.getTeamColor();
//                        int j = 255;
//                        int k = i >> 16 & 255;
//                        int l = i >> 8 & 255;
//                        int i1 = i & 255;
//                        outlinebuffersource.setColor(k, l, i1, 255);
//                    } else {
//                        multibuffersource = bu;
//                    }
//
//                    this.renderEntity(entity, d0, d1, d2, p_109601_, p_109600_, multibuffersource);
//                }
//            }
//        }
//    }

}
