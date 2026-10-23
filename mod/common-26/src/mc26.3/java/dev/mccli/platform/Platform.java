package dev.mccli.platform;

import dev.mccli.mixin.MouseMoveInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.lwjgl.sdl.SDLVideo;

/**
 * Minecraft 26.3 specifics (compiled only into the 26.3 jars; see common-26/src/mc26.2 for 26.2).
 *
 * 26.3 replaced GLFW with SDL3: {@code Window.handle()} is an {@code SDL_Window*}.
 * {@code LivingEntity.swing} takes the item's swing animation and is client-side only
 * (the server infers swings from the action packets).
 */
public final class Platform {
    private Platform() {}

    /** Raise/focus the window. */
    public static boolean raiseWindow(long windowHandle) {
        return SDLVideo.SDL_RaiseWindow(windowHandle);
    }

    /** Show the window (un-hide). */
    public static void showWindow(long windowHandle) {
        SDLVideo.SDL_ShowWindow(windowHandle);
    }

    /** Hide the window (headless mode). */
    public static void hideWindow(long windowHandle) {
        SDLVideo.SDL_HideWindow(windowHandle);
    }

    /** Whether the window is currently shown by the OS. */
    public static boolean isWindowVisible(long windowHandle) {
        return (SDLVideo.SDL_GetWindowFlags(windowHandle) & SDLVideo.SDL_WINDOW_HIDDEN) == 0L;
    }

    /** Resize the window (logical size; the framebuffer follows the display pixel density). */
    public static void setWindowSize(long windowHandle, int width, int height) {
        SDLVideo.SDL_SetWindowSize(windowHandle, width, height);
    }

    /** Swing the arm for a use/right-click action, as vanilla does. */
    public static void swingUse(LocalPlayer player, InteractionHand hand) {
        player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
    }

    /** Swing the main hand for an attack/left-click action, as vanilla does. */
    public static void swingAttack(LocalPlayer player) {
        player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
    }

    /** Whether a keyboard key is held now. */
    public static boolean isKeyDown(Minecraft client, int key) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(key);
    }

    /** An offscreen RGBA render target with depth, for drawing the GUI a second time. */
    public static com.mojang.blaze3d.pipeline.RenderTarget newCanvas(String name, int width, int height) {
        return new com.mojang.blaze3d.pipeline.TextureTarget(name, width, height,
            com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
    }

    /** Move the cursor to window coordinates through the window's own cursor callback. */
    public static void moveCursor(Minecraft client, long windowHandle, double x, double y) {
        MouseHandler mouse = client.mouseHandler;
        ((MouseMoveInvoker) mouse).mccli$onMove(windowHandle, x, y, x - mouse.xpos(), y - mouse.ypos());
    }
}
