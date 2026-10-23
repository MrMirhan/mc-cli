package dev.mccli.control;

import dev.mccli.util.HeadlessMode;
import dev.mccli.video.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** The corner badge the player sees while a tool controls the game. Never part of a screenshot or recording. */
public final class ControlBadge {
    private static final AtomicInteger hiddenBy = new AtomicInteger();
    private static volatile boolean drawn;

    private ControlBadge() {}

    /** Whether the last frame had the badge in it. */
    public static boolean drawn() {
        return drawn;
    }

    public static void hide() {
        hiddenBy.incrementAndGet();
    }

    public static void show() {
        hiddenBy.decrementAndGet();
    }

    public static void extract(Minecraft client, GuiGraphicsExtractor g) {
        ControlPolicy policy = ControlRuntime.policy();
        drawn = policy.active() && !HeadlessMode.isRequested() && hiddenBy.get() == 0 && !Recorder.INSTANCE.isActive();
        if (!drawn) {
            return;
        }
        Component text = Component.translatable(policy.paused() ? "mccli.badge.paused" : "mccli.badge.active");
        int w = client.font.width(text) + 8;
        int x = g.guiWidth() - w - 4;
        g.fill(x, 4, x + w, 18, policy.paused() ? 0xC0806000 : 0xC0A01010);
        g.text(client.font, text, x + 4, 7, 0xFFFFFFFF);
    }
}
