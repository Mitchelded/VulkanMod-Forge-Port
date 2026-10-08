package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.vulkanmod.config.video.VideoModeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * VulkanMod settings, stored as JSON in {@code config/vulkanmod_settings.json}.
 */
public class Config {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    /** System properties that force Vulkan validation layers on (development), regardless of the config file. */
    public static final String[] VALIDATION_PROPERTIES = {"vulkan.validation", "vulkanmod.validation"};

    public int frameQueueSize = 2;
    /**
     * Fullscreen video mode. {@code null} until {@link #resolveRuntimeDefaults} fills it from the monitor; the Fabric
     * version computed it in a field initializer, which needs GLFW and made the class impossible to load in tests.
     */
    public VideoModeSet.VideoMode videoMode = null;
    public boolean windowedFullscreen = false;

    public int advCulling = 2;
    public boolean indirectDraw = false;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public int device = -1;

    public int ambientOcclusion = 1;

    /**
     * Enable the Khronos validation layer (VK_LAYER_KHRONOS_validation) and route its messages to the log.
     * Development only: requires the Vulkan SDK / layer to be installed and costs a lot of performance.
     */
    public boolean validation = false;

    /**
     * Development only: a folder containing {@code VkLayer_khronos_validation.json} (+ dll/so) to use when the Vulkan
     * SDK is not installed. Added to the loader search path ({@code VK_ADD_LAYER_PATH}) in-process before the instance
     * is created. Ignored by the Vulkan loader for elevated (administrator) processes. Empty = SDK/registry layers only.
     */
    public String validationLayerPath = "";

    private static Path CONFIG_PATH;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public void write() {
        if (CONFIG_PATH == null)
            return;

        try {
            // Fabric bug fixed: it created a *directory* at the file path when the parent was missing.
            Path parent = CONFIG_PATH.toAbsolutePath().getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to write VulkanMod config to {}", CONFIG_PATH, e);
        }
    }

    /**
     * Loads the config from {@code path}. Returns {@code null} when the file does not exist. A malformed file is
     * moved aside to {@code <name>.invalid} (and logged) and {@code null} is returned, so defaults are used instead of
     * crashing the game.
     */
    public static Config load(Path path) {
        Config.CONFIG_PATH = path;

        if (!Files.exists(path))
            return null;

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Config config = GSON.fromJson(reader, Config.class);
            if (config == null)
                throw new JsonParseException("empty file");
            config.sanitize();
            return config;
        } catch (IOException | JsonParseException e) {
            Path invalid = path.resolveSibling(path.getFileName() + ".invalid");
            LOGGER.warn("VulkanMod config {} is unreadable ({}); using defaults. The old file was moved to {}",
                    path, e.getMessage(), invalid);
            try {
                Files.move(path, invalid, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                LOGGER.warn("Could not move invalid config aside", moveError);
            }
            return null;
        }
    }

    /** Clamps values that would otherwise break the renderer (e.g. hand-edited files). */
    void sanitize() {
        // Ranges/values mirror the options screen (config/option/Options.java).
        frameQueueSize = Math.max(2, Math.min(5, frameQueueSize));
        if (advCulling != 1 && advCulling != 2 && advCulling != 3 && advCulling != 10) // 10 = culling off
            advCulling = 2;
        ambientOcclusion = Math.max(0, Math.min(2, ambientOcclusion));                  // LightMode FLAT..SUB_BLOCK
        if (device < -1)
            device = -1;
    }

    /** Fills values that can only be computed at runtime (needs GLFW). */
    public void resolveRuntimeDefaults(Supplier<VideoModeSet.VideoMode> defaultVideoMode) {
        if (videoMode == null)
            videoMode = defaultVideoMode.get();
    }

    /** True if validation is enabled in the config or forced with {@code -Dvulkan.validation=true}. */
    public boolean isValidationRequested() {
        for (String key : VALIDATION_PROPERTIES) {
            if (Boolean.parseBoolean(System.getProperty(key)))
                return true;
        }
        return validation;
    }
}
