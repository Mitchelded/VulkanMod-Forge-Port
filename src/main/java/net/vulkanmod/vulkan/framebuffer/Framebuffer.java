package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.DYNAMIC_RENDERING;
import static org.lwjgl.vulkan.VK10.*;

public class Framebuffer {
    public static final int DEFAULT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

//    private long id;

    protected int format;
    protected int depthFormat;
    protected int width, height;
    protected boolean linearFiltering;
    protected boolean depthLinearFiltering;
    protected int attachmentCount;

    boolean hasColorAttachment;
    boolean hasDepthAttachment;

    private List<VulkanImage> colorAttachments = List.of();
    protected VulkanImage depthAttachment;

    private final ObjectArrayList<RenderPass> renderPasses = new ObjectArrayList<>();

    private final Reference2LongArrayMap<RenderPass> framebufferIds = new Reference2LongArrayMap<>();

    //SwapChain
    protected Framebuffer() {}

    public Framebuffer(Builder builder) {
        this.format = builder.format;
        this.depthFormat = builder.depthFormat;
        this.width = builder.width;
        this.height = builder.height;
        this.linearFiltering = builder.linearFiltering;
        this.depthLinearFiltering = builder.depthLinearFiltering;
        this.hasColorAttachment = builder.hasColorAttachment;
        this.hasDepthAttachment = builder.hasDepthAttachment;

        if (builder.createImages)
            this.createImages();
        else {
            this.colorAttachments = builder.colorAttachments;
            this.depthAttachment = builder.depthAttachment;
        }
    }

    public void addRenderPass(RenderPass renderPass) {
        this.renderPasses.add(renderPass);
    }

    public void createImages() {
        if (this.hasColorAttachment) {
            this.colorAttachments = List.of(VulkanImage.builder(this.width, this.height)
                    .setFormat(format)
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(linearFiltering)
                    .setClamp(true)
                    .createVulkanImage());
        }

        if (this.hasDepthAttachment) {
            this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    depthLinearFiltering, true);

            this.attachmentCount++;
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;

        this.cleanUp();

        this.createImages();
    }

    private long createFramebuffer(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            LongBuffer attachments = stack.mallocLong(colorAttachments.size() + (depthAttachment != null ? 1 : 0));
            for (VulkanImage colorAttachment : colorAttachments)
                attachments.put(colorAttachment.getImageView());
            if (depthAttachment != null)
                attachments.put(depthAttachment.getImageView());
            attachments.flip();

            LongBuffer pFramebuffer = stack.mallocLong(1);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType$Default();
            framebufferInfo.renderPass(renderPass.getId());
            framebufferInfo.width(this.width);
            framebufferInfo.height(this.height);
            framebufferInfo.layers(1);
            framebufferInfo.pAttachments(attachments);

            if (VK10.vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create framebuffer");
            }

            return pFramebuffer.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack) {
        if (!DYNAMIC_RENDERING) {
            long framebufferId = framebufferIds.computeIfAbsent(renderPass, renderPass1 -> createFramebuffer(renderPass));
            renderPass.beginRenderPass(commandBuffer, framebufferId, stack);
        } else {
            renderPass.beginDynamicRendering(commandBuffer, stack);
        }

        Renderer.getInstance().setBoundRenderPass(renderPass);
        Renderer.getInstance().setBoundFramebuffer(this);
    }

    public VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0.0f);
        viewport.y(this.height);
        viewport.width(this.width);
        viewport.height(-this.height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }

    public VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(this.width, this.height);

        return scissor;
    }

    public void cleanUp() {
        cleanUp(true);
    }

    public void cleanUp(boolean cleanImages) {
        if (cleanImages) {
            this.colorAttachments.forEach(VulkanImage::free);

            if (this.depthAttachment != null)
                this.depthAttachment.free();
        }

        final VkDevice device = Vulkan.getVkDevice();
        final var ids = framebufferIds.values().toLongArray();

        MemoryManager.getInstance().addFrameOp(
                () -> Arrays.stream(ids).forEach(id ->
                        vkDestroyFramebuffer(device, id, null))
        );


        framebufferIds.clear();
    }

    public long getDepthImageView() {
        return depthAttachment.getImageView();
    }

    public VulkanImage getDepthAttachment() {
        return depthAttachment;
    }

    public VulkanImage getColorAttachment() {
        return colorAttachments.isEmpty() ? null : colorAttachments.get(0);
    }

    /** All color attachments in attachment (fragment output location) order. */
    public List<VulkanImage> getColorAttachments() {
        return colorAttachments;
    }

    public int getColorAttachmentCount() {
        return hasColorAttachment ? getColorAttachments().size() : 0;
    }

    public int[] getFormats() {
        return getColorAttachments().stream().mapToInt(image -> image.format).toArray();
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFormat() {
        return this.format;
    }

    public int getDepthFormat() {
        return this.depthFormat;
    }

    public static Builder builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
        return new Builder(width, height, colorAttachments, hasDepthAttachment);
    }

    public static Builder builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
        return new Builder(List.of(colorAttachment), depthAttachment);
    }

    /** Framebuffer over existing images: color attachment i receives fragment output location i. */
    public static Builder builder(List<VulkanImage> colorAttachments, VulkanImage depthAttachment) {
        return new Builder(colorAttachments, depthAttachment);
    }

    public static class Builder {
        final boolean createImages;
        final int width, height;
        int format, depthFormat;

        List<VulkanImage> colorAttachments = List.of();
        VulkanImage depthAttachment;

//        int colorAttachments;
        boolean hasColorAttachment;
        boolean hasDepthAttachment;

        boolean linearFiltering;
        boolean depthLinearFiltering;

        public Builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            Validate.isTrue(colorAttachments > 0 || hasDepthAttachment, "At least 1 attachment needed");

            //TODO multi color attachments
            Validate.isTrue(colorAttachments <= 1, "Not supported");

            this.createImages = true;
            this.format = DEFAULT_FORMAT;
            this.depthFormat = Vulkan.getDefaultDepthFormat();
            this.linearFiltering = true;
            this.depthLinearFiltering = false;

            this.width = width;
            this.height = height;
            this.hasColorAttachment = colorAttachments == 1;
            this.hasDepthAttachment = hasDepthAttachment;
        }

        public Builder(List<VulkanImage> colorAttachments, VulkanImage depthAttachment) {
            Validate.isTrue(!colorAttachments.isEmpty(), "At least 1 color attachment needed");
            VulkanImage first = colorAttachments.get(0);
            Validate.isTrue(colorAttachments.stream().allMatch(image -> image.width == first.width && image.height == first.height),
                    "Color attachments must have the same size");

            this.createImages = false;
            this.colorAttachments = List.copyOf(colorAttachments);
            this.depthAttachment = depthAttachment;

            this.format = first.format;

            this.width = first.width;
            this.height = first.height;
            this.hasColorAttachment = true;
            this.hasDepthAttachment = depthAttachment != null;

            this.depthFormat = this.hasDepthAttachment ? depthAttachment.format : 0;
            this.linearFiltering = true;
            this.depthLinearFiltering = false;
        }

        public Framebuffer build() {
            return new Framebuffer(this);
        }

        public Builder setFormat(int format) {
            this.format = format;

            return this;
        }

        public Builder setLinearFiltering(boolean b) {
            this.linearFiltering = b;

            return this;
        }

        public Builder setDepthLinearFiltering(boolean b) {
            this.depthLinearFiltering = b;

            return this;
        }

    }
}
