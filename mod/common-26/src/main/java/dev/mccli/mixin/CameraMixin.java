package dev.mccli.mixin;

import dev.mccli.camera.CameraOverride;
import dev.mccli.pilot.Pilot;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow @Final private Quaternionf rotation;

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    /**
     * Right after the camera follows its entity, before update() builds the culling frustum from the camera pose.
     * Applying the pose any later leaves terrain culled from the player's view: vanilla and Sodium both cull with
     * that frustum, so everything behind the player would be missing from override shots.
     */
    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void mccli$applyOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraOverride.Pose p = CameraOverride.INSTANCE.get();
        if (p == null) {
            // the pilot turns the head per tick: give this frame its share of the turn (first person, own view only)
            Minecraft mc = Minecraft.getInstance();
            if (detached || mc.player == null || mc.getCameraEntity() != mc.player) return;
            float[] view = Pilot.VIEW.at(deltaTracker.getGameTimeDeltaPartialTick(false));
            if (view != null) setRotation(view[0], view[1]);
            return;
        }
        setPosition(p.x(), p.y(), p.z());
        setRotation(p.yaw(), p.pitch());
        Double roll = CameraOverride.INSTANCE.roll();
        if (roll != null && roll != 0) rotation.rotateZ((float) Math.toRadians(roll));
        detached = true;   // render the local player's body, like third person
    }
}
