package dev.mccli.mixin;

import dev.mccli.camera.CameraOverride;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The render-only camera's field of view (zooms, dolly zoom) replaces the game's for the world projection. */
@Mixin(Camera.class)
public abstract class CameraFovMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void mccli$cameraFov(float partialTick, CallbackInfoReturnable<Float> cir) {
        Double fov = CameraOverride.INSTANCE.fov();
        if (fov != null && CameraOverride.INSTANCE.get() != null) {
            cir.setReturnValue(fov.floatValue());
        }
    }
}
