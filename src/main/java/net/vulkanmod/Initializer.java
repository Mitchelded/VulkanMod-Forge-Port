package net.vulkanmod;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.vulkanmod.config.Config;
import net.vulkanmod.forge.ClientEvents;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Forge entry point.
 * <p>
 * On Fabric, VulkanMod initialised itself from a {@code ClientModInitializer}, which runs before
 * Minecraft creates its window. On Forge, mod constructors run during {@code ClientModLoader.begin},
 * i.e. <b>after</b> {@code Window.<init>} and {@code RenderSystem.initRenderer}. Everything the window
 * and Vulkan bootstrap need (GLFW platform, video modes, config) therefore lives in {@link #earlyInit()},
 * which the {@code Window} mixin calls before the window is created. The mod constructor only calls it
 * again (a no-op by then) so that a dedicated server or unusual launch order still ends up initialised.
 */
@Mod(Initializer.MOD_ID)
public class Initializer {
    public static final String MOD_ID = "vulkanmod";
    public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    private static String VERSION = "unknown";
    public static Config CONFIG;

    private static boolean earlyInitDone = false;

    public Initializer() {
        // Client-only renderer: tell the server-side connection check we don't need to be present remotely.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (a, b) -> true));

        if (FMLLoader.getDist().isClient()) {
            earlyInit();
            ClientEvents.register();
        } else {
            LOGGER.warn("VulkanMod is a client-only mod; it does nothing on a dedicated server.");
        }
    }

    /**
     * Idempotent early initialisation. Must run on the main (render) thread before the Minecraft window is created.
     */
    public static synchronized void earlyInit() {
        if (earlyInitDone)
            return;
        earlyInitDone = true;

        VERSION = readVersion();
        LOGGER.info("== VulkanMod {} (Forge) ==", VERSION);

        Platform.init();
        VideoModeManager.init();

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("vulkanmod_settings.json");
        CONFIG = loadConfig(configPath);
    }

    private static String readVersion() {
        ModFileInfo fileInfo = FMLLoader.getLoadingModList() != null
                ? FMLLoader.getLoadingModList().getModFileById(MOD_ID)
                : null;
        if (fileInfo == null || fileInfo.getMods().isEmpty())
            return "unknown";
        return fileInfo.getMods().get(0).getVersion().toString();
    }

    private static Config loadConfig(Path path) {
        Config config = Config.load(path);

        if (config == null) {
            config = new Config();
            config.write();
        }

        return config;
    }

    public static String getVersion() {
        return VERSION;
    }
}
