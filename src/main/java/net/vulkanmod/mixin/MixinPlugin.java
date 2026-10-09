package net.vulkanmod.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod/MixinPlugin");

    private static final String PKG = "net.vulkanmod.mixin.";

    private final Map<String, String> incompatibleModByMixin = new HashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        // Mixins we must skip when a specific other mod is present because both transform the same
        // method aggressively (e.g. @Overwrite) and that mod's injectors would otherwise fail with
        // `required=1`. Keep the key as the fully-qualified mixin class name as it appears in the config.
        incompatibleModByMixin.put(PKG + "render.particle.SingleQuadParticleM", "particle_core");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String blockingMod = incompatibleModByMixin.get(mixinClassName);
        if (blockingMod != null && isModLoaded(blockingMod)) {
            LOGGER.info("Skipping {} because mod '{}' also transforms this target; yielding to it for compatibility.",
                    mixinClassName, blockingMod);
            return false;
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        try {
            LoadingModList list = LoadingModList.get();
            return list != null && list.getModFileById(modId) != null;
        } catch (Throwable t) {
            // Mixin plugins can be invoked before FML's loading list is fully usable in unusual
            // bootstrap orders. Treat lookup failure as "mod not present" so we never crash the
            // apply phase just because a compatibility check went sideways.
            LOGGER.debug("isModLoaded({}) failed; assuming absent.", modId, t);
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
