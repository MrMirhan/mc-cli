package dev.mccli.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.3 cursor callback: MouseHandler.onMove(handle, x, y, xrel, yrel); the relative motion only counts
 * while the mouse is grabbed.
 */
@Mixin(MouseHandler.class)
public interface MouseMoveInvoker {
    @Invoker("onMove")
    void mccli$onMove(long handle, double x, double y, double xrel, double yrel);
}
