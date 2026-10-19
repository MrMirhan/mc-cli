package dev.mccli.mixin.headless;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Window.class)
public interface WindowAccess {
    @Invoker("refreshFramebufferSize")
    void mccli$refreshFramebufferSize();
}
