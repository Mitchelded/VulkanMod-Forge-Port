package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class RenderPass {
    Framebuffer framebuffer;
    long id;

    final int attachmentCount;
    final List<AttachmentInfo> colorAttachmentInfos;
    AttachmentInfo depthAttachmentInfo;

    public RenderPass(Framebuffer framebuffer, List<AttachmentInfo> colorAttachmentInfos, AttachmentInfo depthAttachmentInfo) {
        this.framebuffer = framebuffer;
        this.colorAttachmentInfos = colorAttachmentInfos;
        this.depthAttachmentInfo = depthAttachmentInfo;

        this.attachmentCount = colorAttachmentInfos.size() + (depthAttachmentInfo != null ? 1 : 0);

        if (!Vulkan.DYNAMIC_RENDERING) {
            framebuffer.addRenderPass(this);

            createRenderPass();
        }

    }

    private void createRenderPass() {

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.calloc(attachmentCount, stack);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);

            int i = 0;

            // Color attachments: attachment i is fragment output location i
            VkAttachmentReference.Buffer colorAttachmentRefs = VkAttachmentReference.calloc(colorAttachmentInfos.size(), stack);
            for (AttachmentInfo colorAttachmentInfo : colorAttachmentInfos) {
                VkAttachmentDescription colorAttachment = attachments.get(i);
                colorAttachment.format(colorAttachmentInfo.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(colorAttachmentInfo.loadOp)
                        .storeOp(colorAttachmentInfo.storeOp)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(colorAttachmentInfo.finalLayout);

                colorAttachmentRefs.get(i)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                ++i;
            }
            subpass.colorAttachmentCount(colorAttachmentInfos.size());
            subpass.pColorAttachments(colorAttachmentRefs);

            // Depth-Stencil attachment
            if (depthAttachmentInfo != null) {
                VkAttachmentDescription depthAttachment = attachments.get(i);
                depthAttachment.format(depthAttachmentInfo.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(depthAttachmentInfo.loadOp)
                        .storeOp(depthAttachmentInfo.storeOp)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(depthAttachmentInfo.finalLayout);

                VkAttachmentReference depthAttachmentRef = attachmentRefs.get(i)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

                subpass.pDepthStencilAttachment(depthAttachmentRef);
            }

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType$Default()
                    .pAttachments(attachments)
                    .pSubpasses(subpass);

            //Layout transition subpass depency
            int colorFinalLayout = colorAttachmentInfos.isEmpty() ? VK_IMAGE_LAYOUT_UNDEFINED : colorAttachmentInfos.get(0).finalLayout;
            switch (colorFinalLayout) {
                case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                            .srcSubpass(VK_SUBPASS_EXTERNAL)
                            .dstSubpass(0)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                            .srcAccessMask(0)
                            .dstAccessMask(0);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
                case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                            .srcSubpass(0)
                            .dstSubpass(VK_SUBPASS_EXTERNAL)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
            }

            LongBuffer pRenderPass = stack.mallocLong(1);

            if (vkCreateRenderPass(Vulkan.getVkDevice(), renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create render pass");
            }

            id = pRenderPass.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, long framebufferId, MemoryStack stack) {

        if (!colorAttachmentInfos.isEmpty())
            for (VulkanImage colorAttachment : framebuffer.getColorAttachments())
                if (colorAttachment.getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (depthAttachmentInfo != null
                && framebuffer.getDepthAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
            framebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
        renderPassInfo.sType$Default();
        renderPassInfo.renderPass(this.id);
        renderPassInfo.framebuffer(framebufferId);

        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());
        renderPassInfo.renderArea(renderArea);

        // one clear value per attachment, indexed like the attachments
        VkClearValue.Buffer clearValues = VkClearValue.malloc(attachmentCount, stack);
        for (int i = 0; i < colorAttachmentInfos.size(); i++)
            clearValues.get(i).color().float32(VRenderSystem.clearColor);
        if (depthAttachmentInfo != null)
            clearValues.get(colorAttachmentInfos.size()).depthStencil().set(1.0f, 0);

        renderPassInfo.pClearValues(clearValues);

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        Renderer.getInstance().setBoundRenderPass(this);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        vkCmdEndRenderPass(commandBuffer);

        List<VulkanImage> colorAttachments = framebuffer.getColorAttachments();
        for (int i = 0; i < colorAttachmentInfos.size(); i++)
            colorAttachments.get(i).setCurrentLayout(colorAttachmentInfos.get(i).finalLayout);

        if (depthAttachmentInfo != null)
            framebuffer.getDepthAttachment().setCurrentLayout(depthAttachmentInfo.finalLayout);

        Renderer.getInstance().setBoundRenderPass(null);
    }

    public void beginDynamicRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());

        VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
        clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
        clearValues.get(1).depthStencil().set(1.0f, 0);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);

        // Color attachment (dynamic rendering is disabled; only the first attachment is supported here)
        if (!colorAttachmentInfos.isEmpty()) {
            AttachmentInfo colorAttachmentInfo = colorAttachmentInfos.get(0);
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            colorAttachment.imageView(framebuffer.getColorAttachment().getImageView());
            colorAttachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachment.loadOp(colorAttachmentInfo.loadOp);
            colorAttachment.storeOp(colorAttachmentInfo.storeOp);
            colorAttachment.clearValue(clearValues.get(0));

            renderingInfo.pColorAttachments(colorAttachment);
        }

        //Depth attachment
        if (depthAttachmentInfo != null) {
            VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack);
            depthAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            depthAttachment.imageView(framebuffer.getDepthAttachment().getImageView());
            depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthAttachment.loadOp(depthAttachmentInfo.loadOp);
            depthAttachment.storeOp(depthAttachmentInfo.storeOp);
            depthAttachment.clearValue(clearValues.get(1));

            renderingInfo.pDepthAttachment(depthAttachment);
        }

        KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
    }

    public void endDynamicRendering(VkCommandBuffer commandBuffer) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public int getColorAttachmentCount() {
        return colorAttachmentInfos.size();
    }

    public void cleanUp() {
        //TODO

        if (!Vulkan.DYNAMIC_RENDERING)
            MemoryManager.getInstance().addFrameOp(
                    () -> vkDestroyRenderPass(Vulkan.getVkDevice(), this.id, null));

    }

    public long getId() {
        return id;
    }

    public static class AttachmentInfo {
        final Type type;
        final int format;
        int finalLayout;
        int loadOp;
        int storeOp;

        public AttachmentInfo(Type type, int format) {
            this.type = type;
            this.format = format;
            this.finalLayout = type.defaultLayout;

            this.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            this.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }

        public AttachmentInfo setOps(int loadOp, int storeOp) {
            this.loadOp = loadOp;
            this.storeOp = storeOp;

            return this;
        }

        public AttachmentInfo setLoadOp(int loadOp) {
            this.loadOp = loadOp;

            return this;
        }

        public AttachmentInfo setFinalLayout(int finalLayout) {
            this.finalLayout = finalLayout;

            return this;
        }

        public enum Type {
            COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
            DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            final int defaultLayout;

            Type(int layout) {
                defaultLayout = layout;
            }
        }
    }

    public static Builder builder(Framebuffer framebuffer) {
        return new Builder(framebuffer);
    }

    public static class Builder {
        Framebuffer framebuffer;
        List<AttachmentInfo> colorAttachmentInfos = new ArrayList<>();
        AttachmentInfo depthAttachmentInfo;

        public Builder(Framebuffer framebuffer) {
            this.framebuffer = framebuffer;

            for (int format : framebuffer.hasColorAttachment ? framebuffer.getFormats() : new int[0])
                colorAttachmentInfos.add(new AttachmentInfo(AttachmentInfo.Type.COLOR, format).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE));
            if (framebuffer.hasDepthAttachment)
                depthAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.DEPTH, framebuffer.depthFormat).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        }

        public RenderPass build() {
            return new RenderPass(framebuffer, colorAttachmentInfos, depthAttachmentInfo);
        }

        public Builder setLoadOp(int loadOp) {
            colorAttachmentInfos.forEach(info -> info.setLoadOp(loadOp));
            if (depthAttachmentInfo != null) {
                depthAttachmentInfo.setLoadOp(loadOp);
            }


            return this;
        }

        /** The first color attachment (the only one for single-target framebuffers). */
        public AttachmentInfo getColorAttachmentInfo() {
            return colorAttachmentInfos.isEmpty() ? null : colorAttachmentInfos.get(0);
        }

        public List<AttachmentInfo> getColorAttachmentInfos() {
            return colorAttachmentInfos;
        }

        public AttachmentInfo getDepthAttachmentInfo() {
            return depthAttachmentInfo;
        }
    }
}
