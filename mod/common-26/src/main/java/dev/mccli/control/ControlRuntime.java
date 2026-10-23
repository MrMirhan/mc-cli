package dev.mccli.control;

import com.google.gson.JsonObject;
import dev.mccli.McCliMod;
import dev.mccli.platform.Platform;
import dev.mccli.util.ClientCompat;
import dev.mccli.util.HeadlessMode;
import dev.mccli.util.MainThreadExecutor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** The live control state of this game: its policy, its token, the consent screen and the pause key. */
public final class ControlRuntime {
    private static ControlPolicy policy = new ControlPolicy(ControlPolicy.Mode.OFF, java.util.EnumSet.noneOf(AccessClass.class));
    private static AccessToken token;
    private static final AtomicBoolean asking = new AtomicBoolean();
    private static boolean pauseKeyDown;
    private static Path configDir = Path.of("config");

    private ControlRuntime() {}

    public static void init(Path configDir) {
        ControlRuntime.configDir = configDir;
        policy = ControlConfig.load(configDir, HeadlessMode.isRequested());
        try {
            token = AccessToken.load(configDir);
        } catch (IOException e) {
            McCliMod.LOGGER.error("MC-CLI could not create its access token in {}; control stays off", configDir, e);
            policy = new ControlPolicy(ControlPolicy.Mode.OFF, java.util.EnumSet.noneOf(AccessClass.class));
        }
        McCliMod.LOGGER.info("MC-CLI remote control: {} (allow {})", policy.mode(), policy.allowed());
    }

    public static ControlPolicy policy() {
        return policy;
    }

    /** The loader's config folder (mccli.json, mccli-token). */
    public static Path configDir() {
        return configDir;
    }

    public static AccessToken token() {
        return token;
    }

    public static boolean authorized(String presented) {
        return token != null && token.matches(presented);
    }

    /** The request is allowed, or the error code and message to answer with. */
    public static String[] refuse(String command, JsonObject params) {
        return switch (policy.check(command, params)) {
            case ALLOWED -> null;
            case OFF -> new String[]{"CONTROL_OFF", "remote control is off in this game (config/mccli.json remoteControl)"};
            case PAUSED -> new String[]{"CONTROL_PAUSED", "the player paused remote control with F8; wait until they press it again"};
            case CLASS_DENIED -> new String[]{"CONTROL_DENIED", "the player does not allow '" + AccessClass.of(command, params).id()
                + "' commands (config/mccli.json allow)"};
            case CONSENT_DENIED -> new String[]{"CONTROL_DENIED", "the player refused remote control for this game session"};
            case CONSENT_REQUIRED -> {
                askOnce();
                yield new String[]{"CONTROL_CONSENT_REQUIRED", "waiting for the player to accept remote control in the game window; retry after they accept"};
            }
        };
    }

    private static void askOnce() {
        if (!asking.compareAndSet(false, true)) {
            return;
        }
        MainThreadExecutor.runNextTick(() -> {
            Minecraft client = Minecraft.getInstance();
            ClientCompat.setScreen(client, new ConsentScreen(ClientCompat.screen(client), accepted -> {
                policy.answer(accepted);
                asking.set(false);
                McCliMod.LOGGER.info("MC-CLI remote control {} by the player", accepted ? "accepted" : "refused");
            }));
        });
    }

    /** Called every client tick: F8 toggles the pause while control is in use. */
    public static void tick(Minecraft client) {
        if (!policy.active() || client.getWindow() == null) {
            return;
        }
        boolean down = Platform.isKeyDown(client, InputConstants.KEY_F8);
        if (down && !pauseKeyDown) {
            policy.togglePause();
            McCliMod.LOGGER.info("MC-CLI remote control {}", policy.paused() ? "paused" : "resumed");
        }
        pauseKeyDown = down;
    }
}
