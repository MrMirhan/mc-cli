package dev.mccli.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The window's button callback, so the input command clicks the way a real mouse does. The cursor callback
 * changed between 26.2 and 26.3, so moving goes through {@code Platform.moveCursor}.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onButton")
    void mccli$onButton(long handle, MouseButtonInfo buttonInfo, int action);
}
