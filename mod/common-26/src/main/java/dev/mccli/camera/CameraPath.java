package dev.mccli.camera;

import dev.mccli.camera.CameraMath.Vec;
import dev.mccli.camera.CameraOverride.Pose;
import java.util.List;

/**
 * A camera flight through timed keyframes: Catmull-Rom (smooth) or straight lines between them, optionally eased
 * in and out over the whole flight, optionally always aiming at one point.
 */
public final class CameraPath implements CameraMotion {
    public static final int MAX_KEYFRAMES = 64;

    /** {@code t} in ms; yaw and pitch may be null only when the path has a lookAt point; fov on all keyframes or none. */
    public record Keyframe(double t, double x, double y, double z, Float yaw, Float pitch, Double fov) {
        public Keyframe(double t, double x, double y, double z, Float yaw, Float pitch) {
            this(t, x, y, z, yaw, pitch, null);
        }
    }

    private final double[] t, x, y, z, yaw, pitch, fov;
    private final boolean smooth, ease;
    private final Vec lookAt;
    private final double duration;

    private CameraPath(double[] t, double[] x, double[] y, double[] z, double[] yaw, double[] pitch, double[] fov, boolean smooth, boolean ease, Vec lookAt) {
        this.t = t;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fov = fov;
        this.smooth = smooth;
        this.ease = ease;
        this.lookAt = lookAt;
        this.duration = t[t.length - 1];
    }

    /** @throws IllegalArgumentException with a message for the caller when the keyframes cannot make a path */
    public static CameraPath of(List<Keyframe> keys, boolean smooth, boolean ease, Vec lookAt) {
        if (keys.size() < 2) throw new IllegalArgumentException("a path needs at least 2 keyframes");
        if (keys.size() > MAX_KEYFRAMES) throw new IllegalArgumentException("a path takes at most " + MAX_KEYFRAMES + " keyframes");
        int n = keys.size();
        double[] t = new double[n], x = new double[n], y = new double[n], z = new double[n], yaw = new double[n], pitch = new double[n];
        long withFov = keys.stream().filter(k -> k.fov() != null).count();
        if (withFov != 0 && withFov != n) throw new IllegalArgumentException("give fov on every keyframe or on none");
        double[] fov = withFov == 0 ? null : keys.stream().mapToDouble(k -> {
            if (k.fov() < 1 || k.fov() > 170) throw new IllegalArgumentException("fov must be 1-170 degrees");
            return k.fov();
        }).toArray();
        double start = keys.get(0).t();
        for (int i = 0; i < n; i++) {
            Keyframe k = keys.get(i);
            t[i] = k.t() - start;
            if (i > 0 && t[i] <= t[i - 1]) throw new IllegalArgumentException("keyframe times must be strictly increasing (keyframe " + i + ")");
            x[i] = k.x();
            y[i] = k.y();
            z[i] = k.z();
            if (lookAt == null) {
                if (k.yaw() == null || k.pitch() == null) throw new IllegalArgumentException("keyframe " + i + " needs yaw and pitch (or give the path a lookAt point)");
                // Unwrapped, so each step turns the short way round: 170 -> -170 is +20, not -340.
                yaw[i] = i == 0 ? k.yaw() : yaw[i - 1] + wrap(k.yaw() - yaw[i - 1]);
                pitch[i] = k.pitch();
            }
        }
        return new CameraPath(t, x, y, z, yaw, pitch, fov, smooth, ease, lookAt);
    }

    @Override
    public Double fovAt(double ms) {
        if (fov == null) return null;
        int i = segment(ms);
        return curve(fov, i, local(ms, i));
    }

    private double timeAt(double ms) {
        double f = duration <= 0 ? 1 : CameraMotion.clamp01(ms / duration);
        return (ease ? CameraMotion.easeInOut(f) : f) * duration;
    }

    private int segment(double ms) {
        double time = timeAt(ms);
        int i = 0;
        while (i < t.length - 2 && time > t[i + 1]) i++;
        return i;
    }

    private double local(double ms, int i) {
        return (timeAt(ms) - t[i]) / (t[i + 1] - t[i]);
    }

    @Override
    public Pose at(double ms) {
        int i = segment(ms);
        double u = local(ms, i);
        double px = curve(x, i, u), py = curve(y, i, u), pz = curve(z, i, u);
        if (lookAt != null) {
            CameraMath.Angles a = CameraMath.lookAt(new Vec(px, py, pz), lookAt);
            return new Pose(px, py, pz, a.yaw(), a.pitch());
        }
        float p = (float) Math.max(-90, Math.min(90, curve(pitch, i, u)));
        return new Pose(px, py, pz, (float) curve(yaw, i, u), p);
    }

    private double curve(double[] v, int i, double u) {
        double p1 = v[i], p2 = v[i + 1];
        if (!smooth) return p1 + (p2 - p1) * u;
        double p0 = i > 0 ? v[i - 1] : p1;
        double p3 = i + 2 < v.length ? v[i + 2] : p2;
        double u2 = u * u, u3 = u2 * u;
        return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
    }

    /** Degrees into [-180, 180). */
    public static float wrap(double degrees) {
        double d = degrees % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return (float) d;
    }

    @Override
    public long durationMs() {
        return Math.round(duration);
    }

    @Override
    public String kind() {
        return "path";
    }
}
