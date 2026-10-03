package dev.mccli.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The window's key and character callbacks, so the input command presses keys and types text the way a
 * real keyboard does.
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void mccli$keyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void mccli$charTyped(long handle, CharacterEvent event);
}
