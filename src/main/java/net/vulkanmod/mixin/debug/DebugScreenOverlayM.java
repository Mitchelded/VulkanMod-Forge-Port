package net.vulkanmod.mixin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.SystemInfo;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.vulkanmod.Initializer.getVersion;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayM {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private static long bytesToMegabytes(long bytes) {
        return 0;
    }

    @Shadow
    @Final
    private Font font;

    @Shadow
    protected abstract List<String> getGameInformation();

    @Shadow
    protected abstract List<String> getSystemInformation();

    /*
     * This was previously a @Redirect on the Lists.newArrayList(Object[]) call inside getSystemInformation
     * that fully replaced the right-side F3 debug lines with VulkanMod-specific stats. Embeddium's
     * features.gui.hooks.debug.DebugHudMixin also @Redirects an ArrayList-returning call in the same method
     * (redirectRightTextEarly) with required=1, which failed because our redirect had already replaced the
     * target call. Rewritten as @Inject at RETURN that appends VulkanMod lines to the returned list, which
     * (a) doesn't contend for any INVOKE site so Embeddium's mixin applies cleanly, and (b) merges both mods'
     * additions in the on-screen output.
     */
    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void vulkan$appendSystemInformation(CallbackInfoReturnable<List<String>> cir) {
        List<String> strings = cir.getReturnValue();
        if (strings == null) return;

        List<String> extra = new ArrayList<>();
        try {
            long maxMemory = Runtime.getRuntime().maxMemory();
            long totalMemory = Runtime.getRuntime().totalMemory();
            long freeMemory = Runtime.getRuntime().freeMemory();
            long usedMemory = totalMemory - freeMemory;

            Device device = Vulkan.getDevice();

            extra.add("");
            extra.add("VulkanMod " + getVersion());
            if (device != null) {
                extra.add("GPU: " + device.deviceName);
                extra.add("Driver: " + device.driverVersion);
                extra.add("Vulkan: " + device.vkVersion);
            }
            extra.add("CPU: " + SystemInfo.cpuInfo);
            extra.add(String.format("Java: %s %dbit", System.getProperty("java.version"), this.minecraft.is64Bit() ? 64 : 32));
            extra.add(String.format("Mem: % 2d%% %03d/%03dMB", usedMemory * 100L / maxMemory, bytesToMegabytes(usedMemory), bytesToMegabytes(maxMemory)));
            extra.add(String.format("Allocated: % 2d%% %03dMB", totalMemory * 100L / maxMemory, bytesToMegabytes(totalMemory)));
            extra.add("Off-heap: " + getOffHeapMemory() + "MB");
            extra.add("NativeMemory: %dMB".formatted(MemoryManager.getInstance().getNativeMemoryMB()));
            extra.add("DeviceMemory: %dMB".formatted(MemoryManager.getInstance().getAllocatedDeviceMemoryMB()));
            if (WorldRenderer.getInstance() != null) {
                Collections.addAll(extra, WorldRenderer.getInstance().getChunkAreaManager().getStats());
            }
        } catch (Throwable t) {
            extra.add("VulkanMod: (debug info unavailable: " + t.getMessage() + ")");
        }

        strings.addAll(extra);
    }

    private long getOffHeapMemory() {
        return bytesToMegabytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
    }
}
