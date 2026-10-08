package net.vulkanmod.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static checks of {@code vulkanmod.mixins.json}. (Target-method/injection-point checks against the real Minecraft
 * bytecode are done by {@code tools/verify_mixins.py}.)
 */
class MixinConfigTest {

    private static JsonObject config() throws Exception {
        try (InputStream in = MixinConfigTest.class.getResourceAsStream("/vulkanmod.mixins.json")) {
            assertNotNull(in, "vulkanmod.mixins.json not on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void declaresRefmap() throws Exception {
        // Without it Forge production (SRG names) cannot resolve any injection target: "No refMap loaded".
        assertEquals("vulkanmod.refmap.json", config().get("refmap").getAsString());
    }

    @Test
    void everyListedMixinExistsAndIsAMixin() throws Exception {
        JsonObject cfg = config();
        String pkg = cfg.get("package").getAsString();
        Set<String> seen = new HashSet<>();
        for (String section : new String[]{"mixins", "client"}) {
            JsonArray arr = cfg.getAsJsonArray(section);
            if (arr == null)
                continue;
            for (JsonElement e : arr) {
                String name = e.getAsString();
                assertTrue(seen.add(name), "duplicate mixin entry " + name);
                String resource = "/" + (pkg + "." + name).replace('.', '/') + ".class";
                try (InputStream in = MixinConfigTest.class.getResourceAsStream(resource)) {
                    assertNotNull(in, "mixin class missing: " + name);
                    String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    assertTrue(bytes.contains("Lorg/spongepowered/asm/mixin/Mixin;"), name + " has no @Mixin");
                }
            }
        }
        assertTrue(seen.size() > 50, "unexpectedly few mixins: " + seen.size());
    }

    @Test
    void untransformableTargetsAreNotListed() throws Exception {
        // LWJGL core/OpenGL and JOML live in Forge's boot module layer and cannot be mixed into.
        String text = config().toString();
        for (String forbidden : new String[]{"GL11M", "GL15M", "GL30M", "Matrix4fM"})
            assertFalse(text.contains(forbidden), forbidden + " cannot work on Forge");
    }
}
