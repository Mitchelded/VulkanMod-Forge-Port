package net.vulkanmod.mixin.forge;

import net.minecraftforge.client.loading.ClientModLoader;
import net.vulkanmod.forge.LoadingTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Stops FML's early loading screen from drawing into its (destroyed) OpenGL window during mod loading.
 * <p>
 * {@code ClientModLoader} passes {@code ImmediateWindowHandler::renderTick} as the "periodic task" to
 * {@code ModLoader#gatherAndInitializeMods}, {@code #loadMods} and {@code #finishMods}. With the early display enabled
 * that tick runs {@code DisplayWindow#renderThreadFunc}, which makes the early GL context current and renders the
 * progress bars with OpenGL. VulkanMod destroyed that window during the window handoff (see
 * {@code VulkanWindowHandoff}), so the tick would raise a GLFW error, and Minecraft's boot-time GLFW error callback opens
 * a blocking native message box: the game hangs on a white window.
 * <p>
 * The tick is replaced with {@code glfwPollEvents} so the Vulkan window stays responsive while mods load. The calls
 * live in synthetic lambdas whose names depend on javac, hence the {@code "*"} method selector.
 */
@Mixin(value = ClientModLoader.class, remap = false)
public class ClientModLoaderM {

    @ModifyArg(method = "*", index = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/ModLoader;gatherAndInitializeMods(Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V"))
    private static Runnable vulkanmod$gatherTick(Runnable original) {
        return LoadingTick.POLL_EVENTS;
    }

    @ModifyArg(method = "*", index = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/ModLoader;loadMods(Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V"))
    private static Runnable vulkanmod$loadTick(Runnable original) {
        return LoadingTick.POLL_EVENTS;
    }

    @ModifyArg(method = "*", index = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/ModLoader;finishMods(Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V"))
    private static Runnable vulkanmod$finishTick(Runnable original) {
        return LoadingTick.POLL_EVENTS;
    }

}
