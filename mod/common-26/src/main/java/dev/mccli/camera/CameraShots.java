package dev.mccli.camera;

import dev.mccli.camera.CameraMath.Vec;
import dev.mccli.camera.CameraOverride.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Promo camera moves built from the current pose and a subject point, as camera paths (pure, testable):
 * push_in, pull_out, crane_up, crane_down, reveal and dolly_zoom. Follow and attach live in {@link CameraFollow}.
 */
public final class CameraShots {
    private CameraShots() {}

    public static CameraPath build(String shot, Pose from, Vec target, long durationMs, double amount, double fov, Double endFov) {
        if (durationMs < 100) throw new IllegalArgumentException("durationMs must be at least 100");
        Vec start = new Vec(from.x(), from.y(), from.z());
        return switch (shot) {
            case "push_in" -> line(start, toward(start, target, amount <= 0 ? 0.6 : amount), target, durationMs);
            case "pull_out" -> line(start, toward(start, target, amount <= 0 ? 1.6 : amount), target, durationMs);
            case "crane_up" -> line(start, new Vec(start.x(), start.y() + (amount <= 0 ? 6 : amount), start.z()), target, durationMs);
            case "crane_down" -> line(start, new Vec(start.x(), start.y() - (amount <= 0 ? 6 : amount), start.z()), target, durationMs);
            case "reveal" -> reveal(from, start, target, durationMs, amount <= 0 ? 3 : amount);
            case "dolly_zoom" -> dollyZoom(start, target, durationMs, fov, endFov == null ? Math.max(20, fov / 2) : endFov);
            case "fpv_dive" -> fpvDive(start, target, durationMs);
            case "parallax" -> parallax(from, start, target, durationMs, amount <= 0 ? 6 : amount);
            case "orbit_rise" -> orbitRise(start, target, durationMs, amount <= 0 ? 6 : amount);
            case "flyover" -> flyover(start, target, durationMs, amount <= 0 ? 14 : amount);
            default -> throw new IllegalArgumentException(
                "shot must be push_in, pull_out, crane_up, crane_down, reveal, dolly_zoom, fpv_dive, parallax, orbit_rise or flyover");
        };
    }

    /**
     * A drone diving at the subject: starts high behind the camera's position, drops toward the target looking along
     * its flight, banks into the turn and levels out low past the target.
     */
    private static CameraPath fpvDive(Vec start, Vec target, long ms) {
        double dx = target.x() - start.x(), dz = target.z() - start.z();
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double ux = dx / len, uz = dz / len;
        float yaw = CameraMath.lookAt(start, new Vec(target.x(), start.y(), target.z())).yaw();
        Vec p0 = new Vec(start.x() - ux * 4, start.y() + 8, start.z() - uz * 4);
        Vec p1 = new Vec(start.x() + ux * len * 0.45, target.y() + 9, start.z() + uz * len * 0.45);
        Vec p2 = new Vec(target.x() - ux * 2, target.y() + 3.5, target.z() - uz * 2);
        Vec p3 = new Vec(target.x() + ux * 7, target.y() + 2, target.z() + uz * 7);
        return CameraPath.of(List.of(
            new CameraPath.Keyframe(0, p0.x(), p0.y(), p0.z(), yaw, 38f, null, 0.0),
            new CameraPath.Keyframe(ms * 0.4, p1.x(), p1.y(), p1.z(), yaw, 26f, null, 14.0),
            new CameraPath.Keyframe(ms * 0.75, p2.x(), p2.y(), p2.z(), yaw, 10f, null, -9.0),
            new CameraPath.Keyframe(ms, p3.x(), p3.y(), p3.z(), yaw, 4f, null, 0.0)), true, CameraPath.Ease.SMOOTHER, null);
    }

    /** A sideways slide at a fixed heading: near things pass faster than far ones. */
    private static CameraPath parallax(Pose from, Vec start, Vec target, long ms, double blocks) {
        CameraMath.Angles aim = CameraMath.lookAt(start, target);
        Vec right = CameraMath.direction(aim.yaw() + 90, 0);
        Vec end = new Vec(start.x() + right.x() * blocks, start.y(), start.z() + right.z() * blocks);
        return CameraPath.of(List.of(
            new CameraPath.Keyframe(0, start.x(), start.y(), start.z(), aim.yaw(), aim.pitch()),
            new CameraPath.Keyframe(ms, end.x(), end.y(), end.z(), aim.yaw(), aim.pitch())), false, CameraPath.Ease.SMOOTHER, null);
    }

    /** Half a turn round the subject while climbing {@code rise} blocks, always aimed at it. */
    private static CameraPath orbitRise(Vec start, Vec target, long ms, double rise) {
        double r = Math.max(2, Math.hypot(start.x() - target.x(), start.z() - target.z()));
        double a0 = Math.atan2(start.z() - target.z(), start.x() - target.x());
        List<CameraPath.Keyframe> keys = new ArrayList<>();
        int steps = 12;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps, a = a0 + Math.PI * f;
            keys.add(new CameraPath.Keyframe(ms * f, target.x() + Math.cos(a) * r, start.y() + rise * f, target.z() + Math.sin(a) * r, null, null));
        }
        return CameraPath.of(keys, true, CameraPath.Ease.SMOOTHER, target);
    }

    /** A straight pass high over the subject to its far side, looking down at it on the way. */
    private static CameraPath flyover(Vec start, Vec target, long ms, double height) {
        double h = Math.max(start.y(), target.y() + height);
        Vec far = new Vec(2 * target.x() - start.x(), h, 2 * target.z() - start.z());
        return CameraPath.of(List.of(
            new CameraPath.Keyframe(0, start.x(), h, start.z(), null, null),
            new CameraPath.Keyframe(ms * 0.5, target.x(), h, target.z(), null, null),
            new CameraPath.Keyframe(ms, far.x(), far.y(), far.z(), null, null)), true, CameraPath.Ease.SMOOTHER, target);
    }

    /** The point on the line target→start at {@code factor} of the current distance from the target. */
    static Vec toward(Vec start, Vec target, double factor) {
        return new Vec(target.x() + (start.x() - target.x()) * factor, target.y() + (start.y() - target.y()) * factor,
            target.z() + (start.z() - target.z()) * factor);
    }

    private static CameraPath line(Vec a, Vec b, Vec target, long ms) {
        return CameraPath.of(List.of(new CameraPath.Keyframe(0, a.x(), a.y(), a.z(), null, null),
            new CameraPath.Keyframe(ms, b.x(), b.y(), b.z(), null, null)), false, true, target);
    }

    /** Starts looking down at the ground ahead, rises {@code rise} blocks while the view lifts onto the subject. */
    private static CameraPath reveal(Pose from, Vec start, Vec target, long ms, double rise) {
        Vec end = new Vec(start.x(), start.y() + rise, start.z());
        CameraMath.Angles aim = CameraMath.lookAt(end, target);
        float startPitch = Math.min(89, aim.pitch() + 55);
        return CameraPath.of(List.of(new CameraPath.Keyframe(0, start.x(), start.y(), start.z(), aim.yaw(), startPitch),
            new CameraPath.Keyframe(ms, end.x(), end.y(), end.z(), aim.yaw(), aim.pitch())), false, true, null);
    }

    /**
     * The subject keeps its size while the background stretches: the frame width at the subject, 2·d·tan(fov/2),
     * stays the same as the camera moves and the field of view changes.
     */
    private static CameraPath dollyZoom(Vec start, Vec target, long ms, double fov0, double fov1) {
        double d0 = dist(start, target);
        if (d0 < 0.5) throw new IllegalArgumentException("dolly_zoom needs the camera at least half a block from the target");
        double width = 2 * d0 * Math.tan(Math.toRadians(fov0) / 2);
        List<CameraPath.Keyframe> keys = new ArrayList<>();
        int steps = 16;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            double fov = fov0 + (fov1 - fov0) * f;
            double d = width / (2 * Math.tan(Math.toRadians(fov) / 2));
            Vec p = toward(start, target, d / d0);
            keys.add(new CameraPath.Keyframe(ms * f, p.x(), p.y(), p.z(), null, null, fov));
        }
        return CameraPath.of(keys, false, false, target);
    }

    static double dist(Vec a, Vec b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Where a camera sits {@code distance} blocks from {@code anchor} at {@code angle} degrees round it (0 = in front of {@code yaw}), {@code height} above. */
    public static Vec around(Vec anchor, float yaw, double distance, double height, double angle) {
        Vec d = CameraMath.direction((float) (yaw + angle), 0);
        return new Vec(anchor.x() + d.x() * distance, anchor.y() + height, anchor.z() + d.z() * distance);
    }

    /** A point {@code forward}, {@code up} and {@code right} of {@code eye} in the frame of the view {@code yaw}/{@code pitch}. */
    public static Vec attached(Vec eye, float yaw, float pitch, double forward, double up, double right) {
        Vec f = CameraMath.direction(yaw, pitch);
        Vec r = CameraMath.direction(yaw + 90, 0);
        Vec u = new Vec(f.y() * r.z() - f.z() * r.y(), f.z() * r.x() - f.x() * r.z(), f.x() * r.y() - f.y() * r.x());
        // the cross product points down for Minecraft's axes; flip so up is up
        if (u.y() < 0) u = new Vec(-u.x(), -u.y(), -u.z());
        return new Vec(eye.x() + f.x() * forward + u.x() * up + r.x() * right,
            eye.y() + f.y() * forward + u.y() * up + r.y() * right,
            eye.z() + f.z() * forward + u.z() * up + r.z() * right);
    }

    /** Exponential smoothing: moves {@code from} toward {@code to} as a lag of {@code tauMs} would after {@code dtMs}. */
    public static Vec smooth(Vec from, Vec to, double dtMs, double tauMs) {
        if (from == null || tauMs <= 0) return to;
        double k = 1 - Math.exp(-Math.max(0, dtMs) / tauMs);
        return new Vec(from.x() + (to.x() - from.x()) * k, from.y() + (to.y() - from.y()) * k, from.z() + (to.z() - from.z()) * k);
    }
}
