package net.vulkanmod.mixin.window;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.Initializer;
import net.vulkanmod.forge.VulkanWindowHandoff;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Mutable @Final @Shadow private long window;

    @Shadow private boolean vsync;

    @Shadow protected abstract void updateFullscreen(boolean bl);

    @Shadow private boolean fullscreen;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Shadow public abstract int getWidth();

    @Shadow public abstract int getHeight();

    /*
     * We used to @Redirect three OpenGL-setup calls inside Window.<init> to no-ops (glfwWindowHint,
     * glfwMakeContextCurrent, GL.createCapabilities). Other rendering mods - notably Embeddium's
     * workarounds.context_creation.WindowMixin - also @Redirect two of these (wrapGlfwCreateWindow on the
     * setupMinecraftWindow call and postWindowCreated on GL.createCapabilities) with required=1, so our
     * redirects would make their mixin apply fail.
     *
     * Instead we let vanilla's full OpenGL window setup run and swap the result at @Inject(RETURN):
     * VulkanWindowHandoff.handoffExistingWindow destroys the just-created GL window+context and recreates it
     * with GLFW_CLIENT_API=GLFW_NO_API before anyone makes GL calls. glfwWindowHint stays unredirected because
     * on Forge FML's ImmediateWindowHandler returns a pre-existing early-display window that the hints no
     * longer affect, and leaving the hints alone also keeps us out of a priority race with other mods.
     */

    /*
     * Forge port: Forge's patched constructor obtains the window from FML's {@code ImmediateWindowHandler} instead of
     * calling {@code glfwCreateWindow} directly. See {@link VulkanWindowHandoff} for why and how the window is replaced.
     *
     * This split (early-init + hints at HEAD; destroy/recreate at RETURN) replaces the previous @Redirect on
     * ImmediateWindowHandler.setupMinecraftWindow. Other rendering mods — notably Embeddium's
     * workarounds.context_creation.WindowMixin — also @Redirect that exact call site with required=1, so two
     * conflicting redirects cause one of them to be skipped and the skipped mod's mixin apply fails. By leaving
     * vanilla's INVOKE instruction in place and swapping the window afterwards we don't contend for that call site,
     * so both mods' mixins apply cleanly.
     */
    /*
     * All VulkanMod window setup lives in a single @Inject at RETURN. Mixin 0.8.5 (Forge) forbids any @Inject
     * selector other than RETURN/TAIL on constructors, so we cannot run anything before vanilla's ctor body or
     * before specific INVOKE instructions inside it. That is actually fine: the vanilla ctor runs normally
     * (allowing other mods - Embeddium, Oculus - to @Redirect the setupMinecraftWindow call site cleanly) and we
     * then (a) run early initialisation and (b) swap the window to a Vulkan-compatible one if it came out owning
     * a GL context. The window handoff takes care of applying Vulkan GLFW hints before recreating the window.
     */
    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void getHandle(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        Initializer.earlyInit();
        this.window = VulkanWindowHandoff.handoffExistingWindow(this.window, string2);
        VRenderSystem.setWindow(this.window);
    }

    @Inject(method = "updateVsync", at = @At("HEAD"), cancellable = true)
    private void vulkan$updateVsync(boolean vsync, CallbackInfo ci) {
        this.vsync = vsync;
        Vulkan.setVsync(vsync);
        ci.cancel();
    }

    @Inject(method = "toggleFullScreen", at = @At("HEAD"), cancellable = true)
    private void vulkan$toggleFullScreen(CallbackInfo ci) {
        this.fullscreen = !this.fullscreen;
        Options.fullscreenDirty = true;
        ci.cancel();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void vulkan$updateDisplay(CallbackInfo ci) {
        RenderSystem.flipFrame(this.window);

        if (Options.fullscreenDirty) {
            Options.fullscreenDirty = false;
            this.updateFullscreen(this.vsync);
        }
        ci.cancel();
    }

    private boolean wasOnFullscreen = false;

    /*
     * @Inject(HEAD, cancellable) instead of @Overwrite so other mods that @Redirect calls inside
     * vanilla's setMode (e.g. sodiumextras BorderlessWindowMixin redirecting glfwSetWindowMonitor
     * for borderless support) can still inject - their target INVOKEs stay in vanilla's bytecode.
     * We cancel at HEAD, so vanilla's body and the redirects wrapping it never run under Vulkan;
     * runtime behaviour matches the previous @Overwrite exactly.
     */
    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void vulkan$setMode(CallbackInfo ci) {
        Config config = Initializer.CONFIG;
        if (config == null) {
            // Vanilla's Window ctor calls setMode() once before our @Inject(RETURN) runs Initializer.earlyInit().
            // Just cancel - the window is about to be destroyed and recreated at RETURN with the user's config
            // honoured via VulkanWindowHandoff, so there is nothing to configure here.
            ci.cancel();
            return;
        }

        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (this.fullscreen) {
            {
                VideoModeSet.VideoMode videoMode = config.videoMode;

                boolean supported;
                VideoModeSet set = VideoModeManager.getFromVideoMode(videoMode);

                if (set != null) {
                    supported = set.hasRefreshRate(videoMode.refreshRate);
                }
                else {
                    supported = false;
                }

                if(!supported) {
                    LOGGER.error("Resolution not supported, using first available as fallback");
                    videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
                }

                if (!this.wasOnFullscreen) {
                    this.windowedX = this.x;
                    this.windowedY = this.y;
                    this.windowedWidth = this.width;
                    this.windowedHeight = this.height;
                }

                this.x = 0;
                this.y = 0;
                this.width = videoMode.width;
                this.height = videoMode.height;
                GLFW.glfwSetWindowMonitor(this.window, monitor, this.x, this.y, this.width, this.height, videoMode.refreshRate);

                this.wasOnFullscreen = true;
            }
        }
        else if (config.windowedFullscreen) {
            VideoModeSet.VideoMode videoMode = VideoModeManager.getOsVideoMode();

            if (!this.wasOnFullscreen) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            int width = videoMode.width;
            int height = videoMode.height;

            GLFW.glfwSetWindowAttrib(this.window, GLFW_DECORATED, GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(this.window, 0L, 0, 0, width, height, -1);

            this.width = width;
            this.height = height;
            this.wasOnFullscreen = true;
        } else {
            this.x = this.windowedX;
            this.y = this.windowedY;
            this.width = this.windowedWidth;
            this.height = this.windowedHeight;

            GLFW.glfwSetWindowMonitor(this.window, 0L, this.x, this.y, this.width, this.height, -1);
            GLFW.glfwSetWindowAttrib(this.window, GLFW_DECORATED, GLFW_TRUE);

            this.wasOnFullscreen = false;
        }
        ci.cancel();
    }

    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void vulkan$onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        if (window == this.window) {
            if (width > 0 && height > 0) {
                this.framebufferWidth = width;
                this.framebufferHeight = height;
                Renderer.scheduleSwapChainUpdate();
            }
        }
        ci.cancel();
    }

    @Inject(method = "onResize", at = @At("HEAD"), cancellable = true)
    private void vulkan$onResize(long window, int width, int height, CallbackInfo ci) {
        this.width = width;
        this.height = height;

        if (width > 0 && height > 0)
            Renderer.scheduleSwapChainUpdate();
        ci.cancel();
    }

}
