package dev.mccli.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mccli.mixin.GameRendererGuiAccessor;
import dev.mccli.mixin.GuiRenderStateAccessor;
import dev.mccli.mixin.GuiRendererAccessor;
import dev.mccli.platform.Platform;
import dev.mccli.util.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;

import java.util.List;

/**
 * Draws the open screen a second time into an offscreen target cleared to transparent, without the menu background.
 * The screen is extracted again into the game's GUI render state; the deferred tooltip is the last stratum, so a layer
 * keeps or drops it; the game's own GuiRenderer then draws into the canvas. The player's frame is not touched.
 * Same approach as Craftwire's GuiCanvas (UXPLIMA/craftwire, MIT), see NOTICE.
 */
public final class GuiCanvas {
    public record Drawn(boolean screen, boolean tooltip) {}

    private static boolean extracting;
    private static boolean tooltipSeen;
    private static RenderTarget drawingInto;
    private static final RenderTarget[] canvases = new RenderTarget[2];

    private GuiCanvas() {}

    public static boolean extracting() {
        return extracting;
    }

    public static void noteTooltip(boolean shown) {
        tooltipSeen = shown;
    }

    public static RenderTarget drawingInto() {
        return drawingInto;
    }

    /** Render thread. Canvas {@code index} (0 or 1) at this size, created or resized as needed. */
    public static RenderTarget canvas(int width, int height, int index) {
        RenderTarget c = canvases[index];
        if (c != null && (c.width != width || c.height != height)) {
            c.destroyBuffers();
            c = null;
        }
        if (c == null) {
            c = canvases[index] = Platform.newCanvas("MC-CLI GUI canvas " + index, width, height);
        }
        return c;
    }

    /** Render thread. Draws {@code layer} (not WORLD) into {@code target}. */
    public static Drawn draw(GuiLayer layer, RenderTarget target) {
        Minecraft mc = Minecraft.getInstance();
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 1.0);
        Screen screen = ClientCompat.screen(mc);
        if (screen == null) {
            return new Drawn(false, false);
        }
        GuiRenderer renderer = ((GameRendererGuiAccessor) mc.gameRenderer).mccli$guiRenderer();
        GuiRenderState state = ((GuiRendererAccessor) renderer).mccli$renderState();
        var window = mc.getWindow();
        int mx = (int) mc.mouseHandler.getScaledXPos(window);
        int my = (int) mc.mouseHandler.getScaledYPos(window);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        state.reset();
        tooltipSeen = false;
        extracting = true;
        try {
            screen.extractRenderStateWithTooltipAndSubtitles(new GuiGraphicsExtractor(mc, state, mx, my), mx, my, partial);
        } finally {
            extracting = false;
        }
        boolean tooltip = tooltipSeen;
        List<Object> strata = ((GuiRenderStateAccessor) state).mccli$strata();
        if (tooltip && layer == GuiLayer.GUI) {
            strata.removeLast();
        }
        if (layer == GuiLayer.TOOLTIP) {
            Object last = tooltip ? strata.getLast() : null;
            strata.clear();
            if (last != null) {
                strata.add(last);
            }
        }
        drawingInto = target;
        try {
            renderer.render();
        } finally {
            drawingInto = null;
            renderer.endFrame();
        }
        return new Drawn(true, tooltip);
    }
}
