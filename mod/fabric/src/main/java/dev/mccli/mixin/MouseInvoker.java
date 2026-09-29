package dev.mccli.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The GLFW cursor and button callbacks, so the input command drives the same code path as a real mouse.
 */
@Mixin(Mouse.class)
public interface MouseInvoker {
    @Invoker("onCursorPos")
    void mccli$onCursorPos(long window, double x, double y);

    @Invoker("onMouseButton")
    void mccli$onMouseButton(long window, MouseInput input, int action);
}
