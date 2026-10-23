package dev.mccli.mixin;

import dev.mccli.control.ControlBadge;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudBadgeMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void mccli$controlBadge(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        ControlBadge.extract(Minecraft.getInstance(), graphics);
    }
}
