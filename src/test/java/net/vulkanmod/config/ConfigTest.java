package net.vulkanmod.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        for (String key : Config.VALIDATION_PROPERTIES)
            System.clearProperty(key);
    }

    @Test
    void missingFileReturnsNull() {
        assertNull(Config.load(dir.resolve("vulkanmod_settings.json")));
    }

    @Test
    void writeThenLoadRoundTrips() {
        Path file = dir.resolve("vulkanmod_settings.json");
        assertNull(Config.load(file));      // sets the path
        Config c = new Config();
        c.frameQueueSize = 3;
        c.entityCulling = false;
        c.device = 1;
        c.validation = true;
        c.write();

        Config loaded = Config.load(file);
        assertNotNull(loaded);
        assertEquals(3, loaded.frameQueueSize);
        assertFalse(loaded.entityCulling);
        assertEquals(1, loaded.device);
        assertTrue(loaded.validation);
    }

    @Test
    void writeCreatesMissingParentDirectory() {
        // Regression: Fabric created a directory *named like the file* when the parent was missing.
        Path file = dir.resolve("config/sub/vulkanmod_settings.json");
        Config.load(file);
        new Config().write();
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void malformedFileIsMovedAsideAndDefaultsAreUsed() throws Exception {
        Path file = dir.resolve("vulkanmod_settings.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertNull(Config.load(file));
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(dir.resolve("vulkanmod_settings.json.invalid")));
    }

    @Test
    void outOfRangeValuesAreSanitized() throws Exception {
        Path file = dir.resolve("vulkanmod_settings.json");
        Files.writeString(file, "{\"frameQueueSize\": 99, \"advCulling\": 7, \"ambientOcclusion\": -3, \"device\": -9}",
                StandardCharsets.UTF_8);
        Config c = Config.load(file);
        assertNotNull(c);
        assertEquals(5, c.frameQueueSize);
        assertEquals(2, c.advCulling);
        assertEquals(0, c.ambientOcclusion);
        assertEquals(-1, c.device);
    }

    @Test
    void advCullingOffValueIsKept() throws Exception {
        Path file = dir.resolve("vulkanmod_settings.json");
        Files.writeString(file, "{\"advCulling\": 10}", StandardCharsets.UTF_8);
        assertEquals(10, Config.load(file).advCulling);
    }

    @Test
    void validationCanBeForcedBySystemProperty() {
        Config c = new Config();
        assertFalse(c.isValidationRequested());
        System.setProperty("vulkan.validation", "true");
        assertTrue(c.isValidationRequested());
    }

    @Test
    void runtimeDefaultsOnlyFillMissingValues() {
        Config c = new Config();
        assertNull(c.videoMode);
        c.resolveRuntimeDefaults(() -> null);
        assertNull(c.videoMode);
    }
}
