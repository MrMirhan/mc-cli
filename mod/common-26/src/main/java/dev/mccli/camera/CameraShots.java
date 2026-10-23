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
            default -> throw new IllegalArgumentException("shot must be push_in, pull_out, crane_up, crane_down, reveal or dolly_zoom");
        };
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
