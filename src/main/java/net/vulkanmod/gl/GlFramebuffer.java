package net.vulkanmod.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.vulkan.VK11.*;

public class GlFramebuffer {
    private static int ID_COUNTER = 1 << 20; // above raw-GL driver names (GL sink), see GlTexture
    private static final Int2ReferenceOpenHashMap<GlFramebuffer> map = new Int2ReferenceOpenHashMap<>();
    private static int boundId = 0;
    private static GlFramebuffer boundFramebuffer;
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet unknownIdWarned = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    public static void resetBoundFramebuffer() {
        boundFramebuffer = null;
        boundId = 0;
    }

    static void beginRendering(GlFramebuffer framebuffer) {
        Renderer.getInstance().beginRendering(framebuffer.renderPass, framebuffer.framebuffer);

        // beginRendering may end the previous render pass, which resets the bound GL framebuffer;
        // restore it so further attachments (e.g. a RenderTarget created mid-frame) still target it
        boundFramebuffer = framebuffer;
        boundId = framebuffer.id;
    }

    public static int genFramebufferId() {
        int id = ID_COUNTER;
        map.put(id, new GlFramebuffer(id));
        ID_COUNTER++;
        return id;
    }

    public static void bindFramebuffer(int target, int id) {
        // target
        // 36160 GL_FRAMEBUFFER
        // 36161 GL_RENDERBUFFER

        // Read bindings are not emulated and must not redirect where rendering goes
        // (e.g. RenderTarget.copyDepthFrom binds the source as GL_READ_FRAMEBUFFER)
        if(target == GL30.GL_READ_FRAMEBUFFER)
            return;

        // The main target renders to the swapchain pass; its GL framebuffer id (created by vanilla createBuffers on
        // resize) is only an emulation detail. Mods that rebind it directly (e.g. Lodestone's copyDepthBuffer) mean
        // "bind the main target", which is what binding 0 does here.
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        if(mainTarget != null && id == mainTarget.frameBufferId)
            id = 0;

        if(boundId == id)
            return;

        if(id == 0) {
            Renderer.getInstance().endRenderPass();

            if (Renderer.isRecording()) {
                RenderTarget renderTarget = Minecraft.getInstance().getMainRenderTarget();
                renderTarget.bindWrite(true);
            }

            boundFramebuffer = null;
            boundId = 0;
            return;
        }

        GlFramebuffer framebuffer = map.get(id);

        // Unknown name (e.g. created through raw GL in the GL sink context): GL reports GL_INVALID_OPERATION and
        // leaves the binding unchanged; do the same instead of crashing
        if(framebuffer == null) {
            if (unknownIdWarned.add(id))
                Initializer.LOGGER.warn("[VULKAN] glBindFramebuffer: unknown framebuffer {} ignored", id);
            return;
        }

        boundFramebuffer = framebuffer;

        if(boundFramebuffer.framebuffer != null) {
            beginRendering(boundFramebuffer);
        }
    }

    public static void deleteFramebuffer(int id) {
        if(id == 0) {
            return;
        }

        GlFramebuffer framebuffer = map.remove(id);

        // Unknown name: GL silently ignores it
        if(framebuffer == null)
            return;

        if(framebuffer.framebuffer != null)
            framebuffer.cleanUp();
        boundFramebuffer = null;
    }

    public static void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
//        GL30C.glFramebufferTexture2D(target, attachment, texTarget, texture, level);

        // attachment
        // 36064 attachment0
        // 36096 depth attachment

        // texTarget
        // 3553 texture2D

        // Stencil is not emulated: the depth image already backs a combined depth-stencil texture
        if(attachment == GL30.GL_STENCIL_ATTACHMENT)
            return;
        if(attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT)
            attachment = GL30.GL_DEPTH_ATTACHMENT;

        if(attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            throw new UnsupportedOperationException();
        }
        if(texTarget != GL11.GL_TEXTURE_2D) {
            throw new UnsupportedOperationException();
        }
        if(level != 0) {
            throw new UnsupportedOperationException();
        }

        // No emulated framebuffer bound (binding an unknown name was ignored): nothing to attach to
        if(boundFramebuffer == null)
            return;

        boundFramebuffer.setAttachmentTexture(attachment, texture);
    }

    public static void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (boundFramebuffer == null || attachment == GL30.GL_STENCIL_ATTACHMENT)
            return;
        if (attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT)
            attachment = GL30.GL_DEPTH_ATTACHMENT;

        boundFramebuffer.setAttachmentRenderbuffer(attachment, renderbuffer);
    }

    public static int glCheckFramebufferStatus(int target) {
        //TODO
        return GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    public static GlFramebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public static GlFramebuffer getFramebuffer(int id) {
        return map.get(id);
    }

    private final int id;
    Framebuffer framebuffer;
    RenderPass renderPass;

    VulkanImage colorAttachment;
    VulkanImage depthAttachment;

    GlFramebuffer(int i) {
        this.id = i;
    }

    boolean beginRendering() {
        return Renderer.getInstance().beginRendering(this.renderPass, this.framebuffer);
    }

    void setAttachmentTexture(int attachment, int texture) {
        GlTexture glTexture = GlTexture.getTexture(texture);

        // Texture not created through the emulation (e.g. raw GL in the sink context): ignore like an invalid name
        if(glTexture == null)
            return;

        if(glTexture.vulkanImage == null)
            return;

        switch (attachment) {
            case(GL30.GL_COLOR_ATTACHMENT0) ->
                    this.setColorAttachment(glTexture);

            case(GL30.GL_DEPTH_ATTACHMENT) ->
                    this.setDepthAttachment(glTexture);

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setAttachmentRenderbuffer(int attachment, int texture) {
        GlRenderbuffer renderbuffer = GlRenderbuffer.getRenderbuffer(texture);

        if(renderbuffer == null)
            throw new NullPointerException(String.format("Texture %d is null", texture));

        if(renderbuffer.vulkanImage == null)
            return;

        switch (attachment) {
            case(GL30.GL_COLOR_ATTACHMENT0) ->
                    this.setColorAttachment(renderbuffer);

            case(GL30.GL_DEPTH_ATTACHMENT) ->
                    this.setDepthAttachment(renderbuffer);

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setColorAttachment(GlTexture texture) {
        this.colorAttachment = texture.vulkanImage;
        createAndBind();
    }

    void setDepthAttachment(GlTexture texture) {
        //TODO check if texture is in depth format
        this.depthAttachment = texture.vulkanImage;
        createAndBind();
    }

    void setColorAttachment(GlRenderbuffer texture) {
        this.colorAttachment = texture.vulkanImage;
        createAndBind();
    }

    void setDepthAttachment(GlRenderbuffer texture) {
        //TODO check if texture is in depth format
        this.depthAttachment = texture.vulkanImage;
        createAndBind();
    }

    void createAndBind() {
        //Cannot create without color attachment
        if(this.colorAttachment == null)
            return;

        if(this.framebuffer != null) {
            this.cleanUp();
        }

        boolean hasDepthImage = this.depthAttachment != null;
        VulkanImage depthImage = this.depthAttachment;
//        hasDepthImage = false;
//        VulkanImage depthImage = null;

        this.framebuffer = Framebuffer.builder(this.colorAttachment, depthImage).build();
        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);

        builder.getColorAttachmentInfo()
                .setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if(hasDepthImage)
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_LOAD);

        this.renderPass = builder.build();

        GlFramebuffer.beginRendering(this);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public RenderPass getRenderPass() {
        return renderPass;
    }

    void cleanUp() {
        this.framebuffer.cleanUp(false);
        this.renderPass.cleanUp();

        this.framebuffer = null;
        this.renderPass = null;
    }
}
