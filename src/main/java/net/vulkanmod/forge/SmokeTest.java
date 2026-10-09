package net.vulkanmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.VulkanDiagnostics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Automated in-game smoke test (integration test of the whole renderer in a real client).
 * <p>
 * Enabled by a file {@code vulkanmod-smoketest.properties} in the game directory or by {@code -Dvulkanmod.smokeTest=true}.
 * Runs on the client tick (render thread) as a state machine:
 * <ol>
 *   <li>title screen reached and frames presented</li>
 *   <li>window resized (swapchain recreation) and restored, frames keep being presented</li>
 *   <li>test world created (or loaded), player spawned, chunks compiled and drawn</li>
 *   <li>entities summoned, F3 overlay shown, screenshot taken through the Vulkan read-back path</li>
 *   <li>world left, title screen reached again</li>
 *   <li>client stopped (clean shutdown, Vulkan resources destroyed)</li>
 * </ol>
 * Every step logs {@code [SMOKE]}; the outcome is written to {@code vulkanmod-smoketest-result.txt}. No mouse or
 * keyboard input is synthesised, so it can run unattended on a desktop.
 */
public final class SmokeTest {
    private static final String CONFIG_FILE = "vulkanmod-smoketest.properties";
    private static final String RESULT_FILE = "vulkanmod-smoketest-result.txt";

    private enum State { WAIT_TITLE, RESIZE, RESIZE_RESTORE, ENTER_WORLD, WAIT_WORLD, IN_WORLD, LEAVE, WAIT_TITLE_AGAIN, EXIT, DONE }

    private final Properties props;
    private final Path gameDir;
    private final List<String> report = new ArrayList<>();

    private State state = State.WAIT_TITLE;
    private long stateStart = System.nanoTime();
    private long stateFrames = VulkanDiagnostics.framesRendered();
    private int origWidth, origHeight;
    private boolean commandsSent, screenshotTaken;

    private SmokeTest(Properties props, Path gameDir) {
        this.props = props;
        this.gameDir = gameDir;
    }

    /** Called from the mod constructor; registers the tick listener if the smoke test is enabled. */
    public static void registerIfEnabled(Path gameDir) {
        Path cfg = gameDir.resolve(CONFIG_FILE);
        boolean enabled = Boolean.getBoolean("vulkanmod.smokeTest") || Files.exists(cfg);
        if (!enabled)
            return;

        Properties props = new Properties();
        if (Files.exists(cfg)) {
            try (InputStream in = Files.newInputStream(cfg)) {
                props.load(in);
            } catch (IOException e) {
                Initializer.LOGGER.error("[SMOKE] Could not read {}", cfg, e);
            }
        }
        SmokeTest test = new SmokeTest(props, gameDir);
        MinecraftForge.EVENT_BUS.addListener(test::onClientTick);
        Initializer.LOGGER.info("[SMOKE] Smoke test enabled ({})", props);
    }

    private String prop(String key, String def) {
        return props.getProperty(key, def).trim();
    }

    private double elapsed() {
        return (System.nanoTime() - stateStart) / 1e9;
    }

    private long framesInState() {
        return VulkanDiagnostics.framesRendered() - stateFrames;
    }

    private void next(State s, String passMessage) {
        pass(passMessage);
        state = s;
        stateStart = System.nanoTime();
        stateFrames = VulkanDiagnostics.framesRendered();
    }

    private void pass(String message) {
        String line = "PASS " + message + " (frames total " + VulkanDiagnostics.framesRendered() + ")";
        report.add(line);
        Initializer.LOGGER.info("[SMOKE] {}", line);
    }

    private void fail(String message) {
        String line = "FAIL " + message + " (state " + state + ", frames total " + VulkanDiagnostics.framesRendered() + ")";
        report.add(line);
        Initializer.LOGGER.error("[SMOKE] {}", line);
        writeResult(false);
        state = State.DONE;
        Minecraft.getInstance().stop();
    }

    private void timeout(double seconds, String what) {
        if (elapsed() > seconds)
            fail("timeout after " + seconds + " s waiting for " + what + " " + diag(Minecraft.getInstance()));
    }

    private static String diag(Minecraft mc) {
        return "(screen " + (mc.screen != null ? mc.screen.getClass().getName() : "null")
                + ", overlay " + (mc.getOverlay() != null ? mc.getOverlay().getClass().getName() : "null")
                + ", dim " + (mc.level != null ? mc.level.dimension().location() : "-")
                + ", player " + (mc.player != null ? mc.player.blockPosition().toShortString() + (mc.player.isDeadOrDying() ? " DEAD" : "") : "-")
                + ", sections " + (mc.levelRenderer != null ? mc.levelRenderer.countRenderedChunks() : -1) + ")";
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || state == State.DONE)
            return;

        Minecraft mc = Minecraft.getInstance();
        try {
            tick(mc);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[SMOKE] Exception in smoke test", t);
            fail("exception: " + t);
        }
    }

    private void tick(Minecraft mc) {
        switch (state) {
            case WAIT_TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && framesInState() >= 30)
                    next(Boolean.parseBoolean(prop("resize", "true")) ? State.RESIZE : State.ENTER_WORLD,
                            "title screen rendered");
                else
                    timeout(300, "title screen");
            }
            case RESIZE -> {
                if (origWidth == 0) {
                    origWidth = mc.getWindow().getWidth();
                    origHeight = mc.getWindow().getHeight();
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), origWidth + 160, origHeight + 90);
                    Initializer.LOGGER.info("[SMOKE] resizing window {}x{} -> {}x{}", origWidth, origHeight, origWidth + 160, origHeight + 90);
                } else if (elapsed() > 2 && framesInState() >= 20) {
                    int sw = Vulkan.getSwapChain().getWidth(), sh = Vulkan.getSwapChain().getHeight();
                    if (sw != mc.getWindow().getWidth() || sh != mc.getWindow().getHeight())
                        fail("swapchain " + sw + "x" + sh + " does not match framebuffer " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                    else {
                        GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), origWidth, origHeight);
                        next(State.RESIZE_RESTORE, "window resized, swapchain recreated at " + sw + "x" + sh + ", frames still presented");
                    }
                } else
                    timeout(30, "frames after resize");
            }
            case RESIZE_RESTORE -> {
                if (elapsed() > 2 && framesInState() >= 20)
                    next(State.ENTER_WORLD, "window restored to " + Vulkan.getSwapChain().getWidth() + "x" + Vulkan.getSwapChain().getHeight());
                else
                    timeout(30, "frames after restoring the window size");
            }
            case ENTER_WORLD -> {
                String world = prop("world", "vulkanmod-smoketest");
                if (mc.getLevelSource().levelExists(world)) {
                    Initializer.LOGGER.info("[SMOKE] loading existing world '{}'", world);
                    mc.createWorldOpenFlows().loadLevel(mc.screen, world);
                } else {
                    Initializer.LOGGER.info("[SMOKE] creating world '{}'", world);
                    LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT);
                    WorldOptions options = new WorldOptions(Long.parseLong(prop("seed", "4242")), true, false);
                    mc.createWorldOpenFlows().createFreshLevel(world, settings, options, WorldPresets::createNormalWorldDimensions);
                }
                next(State.WAIT_WORLD, "world load requested");
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null && mc.levelRenderer.countRenderedChunks() > 0) {
                    stepStart = System.nanoTime();
                    next(State.IN_WORLD, "world joined; " + mc.levelRenderer.countRenderedChunks() + " chunk sections rendered");
                }
                else
                    timeout(240, "world to load and render");
            }
            case IN_WORLD -> {
                if (Boolean.parseBoolean(prop("extended", "false"))) {
                    extendedWorldTick(mc);
                    return;
                }
                double seconds = Double.parseDouble(prop("worldSeconds", "20"));
                if (!commandsSent && elapsed() > 2) {
                    commandsSent = true;
                    mc.player.connection.sendCommand("time set 1000");
                    mc.player.connection.sendCommand("gamerule doDaylightCycle false");
                    mc.player.connection.sendCommand("summon minecraft:pig ^1 ^ ^4");
                    mc.player.connection.sendCommand("summon minecraft:sheep ^-1 ^ ^4");
                    mc.player.connection.sendCommand("summon minecraft:armor_stand ^ ^ ^3");
                    mc.options.renderDebug = Boolean.parseBoolean(prop("f3", "true"));
                    pass("commands sent (entities summoned)");
                }
                if (!screenshotTaken && elapsed() > seconds * 0.75) {
                    screenshotTaken = true;
                    Screenshot.grab(gameDir.toFile(), "vulkanmod-smoketest.png", mc.getMainRenderTarget(),
                            msg -> Initializer.LOGGER.info("[SMOKE] screenshot: {}", msg.getString()));
                    pass("screenshot requested via Vulkan read-back");
                }
                if (elapsed() > seconds) {
                    if (framesInState() < seconds * 5)
                        fail("only " + framesInState() + " frames in " + seconds + " s in world");
                    else
                        next(State.LEAVE, "stayed in world " + seconds + " s, " + framesInState() + " frames, "
                                + mc.levelRenderer.countRenderedChunks() + " sections visible, "
                                + mc.level.getEntityCount() + " entities");
                }
            }
            case LEAVE -> {
                mc.options.renderDebug = false;
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local)
                    mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Saving world")));
                else
                    mc.clearLevel();
                mc.setScreen(new TitleScreen());
                next(State.WAIT_TITLE_AGAIN, "left the world");
            }
            case WAIT_TITLE_AGAIN -> {
                if (mc.level == null && mc.screen instanceof TitleScreen && framesInState() >= 30) {
                    if (Boolean.parseBoolean(prop("extended", "false")) && !reopened) {
                        reopened = true;
                        step = 0;
                        next(State.ENTER_WORLD, "returned to title screen (world saved); reopening it");
                    } else
                        next(State.EXIT, "returned to title screen");
                }
                else
                    timeout(120, "title screen after leaving the world");
            }
            case EXIT -> {
                pass("requesting clean shutdown; validation errors so far: " + Vulkan.VALIDATION_ERRORS.get());
                writeResult(true);
                state = State.DONE;
                if (Boolean.parseBoolean(prop("exit", "true")))
                    mc.stop();
            }
            case DONE -> {}
        }
    }

    // ---- extended in-world checks (prop extended=true): entities, block entities, particles, GUI, resource reload,
    // dimension change, then save/quit and reopen. Commands run on the integrated server at op level, so they work
    // in worlds without cheats; use a copy of a real world.
    private int step = 0;
    private boolean reopened;
    private long stepStart = System.nanoTime();
    private CompletableFuture<Void> reload;
    private double ox, oy, oz;

    private double stepElapsed() {
        return (System.nanoTime() - stepStart) / 1e9;
    }

    private void nextStep(String passMessage) {
        if (passMessage != null)
            pass(passMessage);
        step++;
        stepStart = System.nanoTime();
    }

    private void serverCmd(Minecraft mc, String cmd) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null)
            return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null)
                return;
            int r = server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4), cmd);
            Initializer.LOGGER.info("[SMOKE] /{} -> {}", cmd, r);
        });
    }

    private void shot(Minecraft mc, String name) {
        Screenshot.grab(gameDir.toFile(), "smoke-" + name + ".png", mc.getMainRenderTarget(),
                msg -> Initializer.LOGGER.info("[SMOKE] screenshot {}: {}", name, msg.getString()));
        pass("screenshot " + name + " (" + mc.levelRenderer.countRenderedChunks() + " sections, "
                + (mc.level != null ? mc.level.getEntityCount() : 0) + " entities, dim "
                + (mc.level != null ? mc.level.dimension().location() : "-") + ")");
    }

    private boolean worldReady(Minecraft mc, ResourceKey<Level> dim) {
        return mc.level != null && mc.player != null && mc.screen == null && mc.getOverlay() == null
                && mc.level.dimension() == dim && mc.levelRenderer.countRenderedChunks() > 0;
    }

    private void extendedWorldTick(Minecraft mc) {
        double t = stepElapsed();
        if (t > 120)
            fail("timeout in extended step " + step + " " + diag(mc));
        if (state == State.DONE)
            return;

        if (reopened) {
            if (t > 8) {
                shot(mc, "reopened-world");
                next(State.LEAVE, "saved world reopened and rendered");
            }
            return;
        }

        switch (step) {
            case 0 -> {
                if (t < 2) return;
                ox = mc.player.getX(); oy = mc.player.getY(); oz = mc.player.getZ();
                // face south (+Z), clear a pocket in front, then place everything at world-relative offsets
                for (String c : new String[]{"gamemode creative", "time set day", "weather clear", "gamerule doDaylightCycle false",
                        "tp @s ~ ~ ~ 0 20", "fill ~-4 ~ ~2 ~4 ~3 ~9 minecraft:air",
                        "summon minecraft:pig ~1 ~ ~5 {NoAI:1b}", "summon minecraft:sheep ~-1 ~ ~5 {NoAI:1b}", "summon minecraft:armor_stand ~ ~ ~4",
                        "summon minecraft:zombie ~2 ~ ~7 {NoAI:1b}", "summon tfc:cow ~-2 ~ ~7 {NoAI:1b}",
                        "setblock ~-3 ~ ~4 minecraft:chest", "setblock ~-3 ~ ~6 tfc:firepit", "setblock ~3 ~ ~4 gtceu:lv_electric_furnace",
                        "setblock ~3 ~ ~6 minecraft:oak_sign"})
                    serverCmd(mc, c);
                mc.options.renderDebug = Boolean.parseBoolean(prop("f3", "false"));
                nextStep("entities summoned and block entities placed at " + (int) ox + " " + (int) oy + " " + (int) oz);
            }
            case 1 -> {
                if (t < 4) return;
                serverCmd(mc, "particle minecraft:flame ~ ~1 ~3 0.6 0.6 0.6 0.02 400 force");
                serverCmd(mc, "particle minecraft:happy_villager ~ ~1 ~3 1 1 1 0 200 force");
                nextStep("particles spawned");
            }
            case 2 -> {
                if (t < 0.5) return;
                shot(mc, "overworld-entities-particles");
                nextStep(null);
            }
            case 3 -> {
                if (t < 2) return;
                mc.setScreen(new InventoryScreen(mc.player));
                nextStep("inventory screen opened: " + (mc.screen != null ? mc.screen.getClass().getName() : "null"));
            }
            case 4 -> {
                if (t < 3) return;
                shot(mc, "inventory-gui");
                nextStep(null);
            }
            case 5 -> {
                // keep the GUI open so it can also be captured from outside the game
                if (t < 6) return;
                nextStep(null);
            }
            case 6 -> {
                if (t < 1) return;
                mc.setScreen(null);
                reload = mc.reloadResourcePacks();
                nextStep("resource reload started");
            }
            case 7 -> {
                if (reload.isDone() && mc.getOverlay() == null && mc.screen == null && t > 3) {
                    if (reload.isCompletedExceptionally())
                        fail("resource reload failed");
                    else {
                        shot(mc, "after-resource-reload");
                        nextStep("resource reload completed");
                    }
                }
            }
            case 8 -> {
                if (t < 2) return;
                serverCmd(mc, "execute in minecraft:the_nether run tp @s 0 100 0");
                nextStep("teleport to the_nether requested");
            }
            case 9 -> {
                if (worldReady(mc, Level.NETHER) && t > 8) {
                    shot(mc, "nether");
                    nextStep("dimension change to the_nether rendered");
                }
            }
            case 10 -> {
                if (t < 2) return;
                serverCmd(mc, String.format(java.util.Locale.ROOT, "execute in minecraft:overworld run tp @s %.1f %.1f %.1f", ox, oy, oz));
                nextStep("teleport back to the overworld requested");
            }
            case 11 -> {
                if (worldReady(mc, Level.OVERWORLD) && t > 8) {
                    shot(mc, "back-in-overworld");
                    next(State.LEAVE, "dimension change back to the overworld rendered");
                }
            }
        }
    }

    private void writeResult(boolean passed) {
        List<String> lines = new ArrayList<>(report);
        lines.add(0, (passed ? "RESULT PASS" : "RESULT FAIL") + " frames=" + VulkanDiagnostics.framesRendered());
        try {
            Files.write(gameDir.resolve(RESULT_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Initializer.LOGGER.error("[SMOKE] Could not write result file", e);
        }
    }
}

