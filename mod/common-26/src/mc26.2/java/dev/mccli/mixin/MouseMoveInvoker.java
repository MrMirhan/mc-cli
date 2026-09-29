package dev.mccli.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.2 cursor callback: MouseHandler.onMove(handle, x, y), private.
 */
@Mixin(MouseHandler.class)
public interface MouseMoveInvoker {
    @Invoker("onMove")
    void mccli$onMove(long handle, double x, double y);
}
