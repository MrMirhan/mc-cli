package dev.mccli.capture;

import dev.mccli.commands.GuiCommand;
import dev.mccli.mixin.AbstractContainerScreenAccessor;
import dev.mccli.util.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * A GUI scale for one capture. The screen is laid out again, so the cursor moves to stay over the slot it hovered
 * (the tooltip stays shown), else over the same point relative to the centre.
 */
public final class GuiScale {
    private GuiScale() {}

    /** Render thread. Returns the scale to put back afterwards. */
    public static int apply(Minecraft mc, int scale) {
        int before = mc.options.guiScale().get();
        if (before == scale) {
            return before;
        }
        var window = mc.getWindow();
        Screen screen = ClientCompat.screen(mc);
        Slot hovered = screen instanceof AbstractContainerScreen<?> c ? ((AbstractContainerScreenAccessor) c).mccli$getHoveredSlot() : null;
        double dx = mc.mouseHandler.getScaledXPos(window) - window.getGuiScaledWidth() / 2.0;
        double dy = mc.mouseHandler.getScaledYPos(window) - window.getGuiScaledHeight() / 2.0;
        mc.options.guiScale().set(scale);
        mc.resizeGui();
        if (screen == null || ClientCompat.screen(mc) != screen) {
            return before;
        }
        if (hovered != null) {
            AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
            GuiCommand.moveCursor(mc, at.mccli$getLeftPos() + hovered.x + 8, at.mccli$getTopPos() + hovered.y + 8);
        } else {
            GuiCommand.moveCursor(mc, window.getGuiScaledWidth() / 2.0 + dx, window.getGuiScaledHeight() / 2.0 + dy);
        }
        return before;
    }
}
