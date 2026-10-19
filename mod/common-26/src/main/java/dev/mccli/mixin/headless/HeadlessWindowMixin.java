package dev.mccli.mixin.headless;

import com.mojang.blaze3d.platform.Window;
import dev.mccli.util.HeadlessMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Headless mode: make sure the game window is hidden once it exists. The window is normally created
 * hidden already (see the version specific creation mixins); this also covers NeoForge, which hands
 * over its (visible) early loading window.
 *
 * <p>While hidden the framebuffer is mccli.headless.width x height whatever the real window is: Windows
 * clamps even a hidden window to the screen, so a 1080x1920 frame on a 1080p monitor came out 1080x1061.
 */
@Mixin(Window.class)
public abstract class HeadlessWindowMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void mccli$hideOnCreate(CallbackInfo ci) {
        HeadlessMode.onWindowCreated(((Window) (Object) this).handle());
    }

    @Inject(method = "refreshFramebufferSize", at = @At("TAIL"))
    private void mccli$fixedFramebuffer(CallbackInfo ci) {
        if (HeadlessMode.isHidden()) {
            Window window = (Window) (Object) this;
            window.setWidth(HeadlessMode.width());
            window.setHeight(HeadlessMode.height());
        }
    }

    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void mccli$ignoreResizeWhileHidden(CallbackInfo ci) {
        if (HeadlessMode.isHidden()) ci.cancel();
    }
}
