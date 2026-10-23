package dev.mccli.mixin;

import com.google.gson.JsonObject;
import dev.mccli.events.EventLog;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Screens opening and closing, for the event log. */
@Mixin(Gui.class)
public abstract class GuiEventMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void mccli$screenChanged(Screen screen, CallbackInfo ci) {
        JsonObject e = new JsonObject();
        if (screen != null) {
            e.addProperty("screen", screen.getClass().getSimpleName());
            e.addProperty("title", screen.getTitle().getString());
        }
        EventLog.add(screen != null ? "screen_open" : "screen_close", e);
    }
}
