package dev.mccli.mixin;

import dev.mccli.events.EventLog;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Titles, subtitles and action bar messages, for the event log. */
@Mixin(Hud.class)
public abstract class HudTitleMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void mccli$title(Component text, CallbackInfo ci) {
        EventLog.add("title", "text", text.getString());
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void mccli$subtitle(Component text, CallbackInfo ci) {
        EventLog.add("subtitle", "text", text.getString());
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void mccli$actionbar(Component text, boolean animate, CallbackInfo ci) {
        EventLog.add("actionbar", "text", text.getString());
    }
}
