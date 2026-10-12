package dev.mccli.mixin;

import dev.mccli.video.Recorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands each finished frame (world, HUD and screens drawn, not yet shown) to the video recorder. */
@Mixin(Minecraft.class)
public abstract class FrameRenderedMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private void mccli$frameRendered(boolean advanceGameTime, CallbackInfo ci) {
        Recorder.INSTANCE.onFrame();
    }
}
