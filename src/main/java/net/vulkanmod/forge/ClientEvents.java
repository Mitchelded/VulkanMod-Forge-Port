package net.vulkanmod.forge;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.vulkanmod.config.gui.VOptionScreen;

/**
 * Forge game-bus listeners that replace Fabric-only mixins.
 */
public final class ClientEvents {

    private ClientEvents() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onScreenOpening);
    }

    /**
     * Replaces vanilla's "Video Settings" screen with VulkanMod's settings screen.
     * The Fabric build did this by injecting into a synthetic lambda of {@code OptionsScreen#init}
     * (intermediary name {@code method_19828}), which has a different name on Forge; the event is stable.
     */
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof VideoSettingsScreen) {
            Screen parent = event.getCurrentScreen();
            event.setNewScreen(new VOptionScreen(Component.literal("Video Setting"), parent));
        }
    }
}
