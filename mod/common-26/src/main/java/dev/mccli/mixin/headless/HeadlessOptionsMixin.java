package dev.mccli.mixin.headless;

import dev.mccli.util.HeadlessMode;
import dev.mccli.video.AudioTap;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Headless mode: mute the master volume as seen by the sound engine, without changing the saved
 * option (so options.txt is never written with a zero volume). A recording's loopback device plays to nobody,
 * so it keeps the sound.
 */
@Mixin(Options.class)
public abstract class HeadlessOptionsMixin {
    @Inject(method = "getSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void mccli$muteWhileHeadless(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (source == SoundSource.MASTER && HeadlessMode.isMuted() && !AudioTap.armed()) {
            cir.setReturnValue(0.0F);
        }
    }
}
