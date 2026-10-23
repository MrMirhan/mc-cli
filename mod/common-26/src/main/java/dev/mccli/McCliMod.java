package dev.mccli;

import dev.mccli.control.ControlRuntime;
import dev.mccli.events.EventLog;
import dev.mccli.server.McpServer;
import dev.mccli.server.SocketServer;
import dev.mccli.util.HeadlessMode;
import dev.mccli.util.LogCaptureAppender;
import dev.mccli.util.MainThreadExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * MC-CLI: Minecraft Command-Line Interface for LLM-Assisted Shader Development
 *
 * This mod provides a TCP server that accepts JSON commands to control Minecraft,
 * designed for integration with AI/LLM agents for shader development workflows.
 *
 * Architecture:
 * - SocketServer: Listens on port 25580 for TCP connections
 * - CommandDispatcher: Routes commands to handlers
 * - MainThreadExecutor: Ensures Minecraft operations run on the game thread
 *
 * Commands:
 * - status: Get game state (position, time, shader info)
 * - teleport: Move player to coordinates
 * - camera: Set view direction
 * - time: Control world time
 * - shader: Shader management (list, get, set, reload, errors)
 * - screenshot: Capture screen
 * - perf: Performance metrics
 * - logs: Get game logs
 */
public final class McCliMod {
    public static final String MOD_ID = "mccli";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final int DEFAULT_PORT = 25580;

    private static SocketServer server;
    private static McpServer mcp;
    private static LogCaptureAppender logAppender;
    private static boolean initialized;

    private McCliMod() {}

    /**
     * Loader-independent initialization. Called once by the loader entrypoint
     * (Fabric ClientModInitializer / NeoForge @Mod constructor).
     *
     * @param loader human readable loader name, used for logging only
     * @param configDir the loader's config folder (mccli.json, mccli-token)
     */
    public static synchronized void init(String loader, Path configDir) {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("MC-CLI initializing ({}){}...", loader, HeadlessMode.isRequested() ? " in headless mode" : "");

        // Install log capture appender
        installLogCapture();
        ControlRuntime.init(configDir);

        // MCP first, so the socket server's registry entry carries its port
        mcp = new McpServer();
        mcp.start();
        // Start TCP server with dynamic port allocation
        server = new SocketServer(DEFAULT_PORT);
        server.start();

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) {
                server.stop();
            }
            if (mcp != null) {
                mcp.stop();
            }
            uninstallLogCapture();
        }));

        // Note: Actual bound port is logged by SocketServer after binding
        LOGGER.info("MC-CLI initialized (base port: {})", DEFAULT_PORT);
    }

    /**
     * Must be called by the loader glue at the end of every client tick.
     */
    public static void onClientTick() {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        HeadlessMode.tick(client);
        ControlRuntime.tick(client);
        eventTick(client);
        MainThreadExecutor.processPendingTasks();
    }

    private static int ticks;

    /** Packet counts once a second; while a video records, the player's position twice a second. */
    private static void eventTick(net.minecraft.client.Minecraft client) {
        ticks++;
        if (ticks % 20 == 0) {
            EventLog.flushPacketCounts();
        }
        if (ticks % 10 == 0 && EventLog.sidecarOpen() && client.player != null) {
            com.google.gson.JsonObject p = new com.google.gson.JsonObject();
            p.addProperty("x", Math.round(client.player.getX() * 100) / 100.0);
            p.addProperty("y", Math.round(client.player.getY() * 100) / 100.0);
            p.addProperty("z", Math.round(client.player.getZ() * 100) / 100.0);
            p.addProperty("yaw", Math.round(client.player.getYRot() * 10) / 10.0);
            p.addProperty("pitch", Math.round(client.player.getXRot() * 10) / 10.0);
            EventLog.add("player", p);
        }
    }

    private static void installLogCapture() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            logAppender = new LogCaptureAppender();
            logAppender.start();

            config.addAppender(logAppender);
            LoggerConfig root = config.getRootLogger();
            root.addAppender(logAppender, Level.ALL, null);
            ctx.updateLoggers();
        } catch (Exception e) {
            LOGGER.warn("Failed to install log capture appender", e);
        }
    }

    private static void uninstallLogCapture() {
        try {
            if (logAppender == null) {
                return;
            }
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig root = config.getRootLogger();
            root.removeAppender(logAppender.getName());
            logAppender.stop();
            ctx.updateLoggers();
        } catch (Exception e) {
            LOGGER.warn("Failed to uninstall log capture appender", e);
        }
    }
}
