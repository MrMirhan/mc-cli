package dev.mccli.mixin;

import dev.mccli.capture.GuiCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The canvas pass skips the menu background (darkening, blur); the player's own frame keeps it. */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void mccli$noBackgroundOnCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (GuiCanvas.extracting()) {
            ci.cancel();
        }
    }
}
