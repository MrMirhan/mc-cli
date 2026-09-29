package dev.mccli.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The GLFW key callback, so the input command presses keys the way a real keyboard does.
 */
@Mixin(Keyboard.class)
public interface KeyboardInvoker {
    @Invoker("onKey")
    void mccli$onKey(long window, int action, KeyInput input);
}
