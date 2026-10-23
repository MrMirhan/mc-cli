package dev.mccli.camera;

import dev.mccli.camera.CameraMath.Vec;
import dev.mccli.camera.CameraOverride.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * A camera tied to a moving entity, evaluated every frame on the render thread.
 *
 * follow: stays {@code distance} blocks away at {@code angle} round the entity (0 in front, 180 behind), relative to
 * the entity's heading or fixed in the world, {@code height} up, with a lag of {@code smoothingMs}, always aiming at it.
 * attach: rides along with the entity's view, {@code forward/up/right} of its eyes, turned by {@code yawOffset} and
 * {@code pitchOffset}: the camera turns as the player looks around (a custom third-person view).
 */
public final class CameraFollow implements CameraMotion {
    public enum Mode { FOLLOW, ATTACH }

    public record Settings(Mode mode, String entity, double distance, double height, double angle, boolean relative,
                           double smoothingMs, double forward, double up, double right, float yawOffset,
                           float pitchOffset, long durationMs, Double fov) {}

    private final Settings s;
    private Entity target;
    private Vec last;
    private Pose held;
    private double lastMs = Double.NaN;

    public CameraFollow(Settings s) {
        this.s = s;
    }

    @Override
    public Pose at(double ms) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = entity(mc);
        if (e == null) {
            // the entity left render distance: hold the last pose rather than jump
            return held;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (s.mode() == Mode.ATTACH) {
            net.minecraft.world.phys.Vec3 eye = e.getEyePosition(partial);
            float yaw = e.getViewYRot(partial), pitch = e.getViewXRot(partial);
            Vec p = CameraShots.attached(new Vec(eye.x, eye.y, eye.z), yaw, pitch, s.forward(), s.up(), s.right());
            held = new Pose(p.x(), p.y(), p.z(), yaw + s.yawOffset(), Math.max(-90, Math.min(90, pitch + s.pitchOffset())));
            return held;
        }
        net.minecraft.world.phys.Vec3 pos = e.getPosition(partial);
        Vec anchor = new Vec(pos.x, pos.y + e.getBbHeight() * 0.6, pos.z);
        float heading = s.relative() ? e.getYRot(partial) : 0;
        Vec want = CameraShots.around(anchor, heading, s.distance(), s.height(), s.angle());
        last = Double.isNaN(lastMs) ? want : CameraShots.smooth(last, want, ms - lastMs, s.smoothingMs());
        lastMs = ms;
        CameraMath.Angles aim = CameraMath.lookAt(last, anchor);
        held = new Pose(last.x(), last.y(), last.z(), aim.yaw(), aim.pitch());
        return held;
    }

    private Entity entity(Minecraft mc) {
        if (target != null && !target.isRemoved()) return target;
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Player not in game");
        if (s.entity() == null || s.entity().isEmpty() || s.entity().equals("@self")) {
            target = mc.player;
            return target;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().toString().equalsIgnoreCase(s.entity()) || e.getName().getString().equalsIgnoreCase(s.entity())) {
                target = e;
                return e;
            }
        }
        if (held != null) return null;
        throw new IllegalArgumentException("No loaded entity matches \"" + s.entity() + "\" (UUID or exact name, within render distance)");
    }

    @Override
    public long durationMs() {
        return s.durationMs();
    }

    @Override
    public Double fovAt(double ms) {
        return s.fov();
    }

    @Override
    public String kind() {
        return s.mode() == Mode.ATTACH ? "attach" : "follow";
    }
}
