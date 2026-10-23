package dev.mccli.pilot;

import java.util.Map;

/**
 * "Plays like a person": seeded randomness, per-profile reaction, CPS, aim wander and a spring-damped head, so the
 * pilot never snaps like a bot. Ported from Video Atölyesi's agent/js/human.js; the same seed gives the same moves.
 */
public final class HumanModel {
    private HumanModel() {}

    /** cps: clicks per second for 1.8; reaction ms (mean, sd); aim wander in degrees; turn: head spring frequency. */
    public record Profile(double cpsLo, double cpsHi, double reactionMean, double reactionSd, double aim, double miss,
                          double turn, double strafe, double wtap, double crit, double retreatAt) {}

    public static final Map<String, Profile> PROFILES = Map.of(
        "rookie", new Profile(6, 9, 290, 60, 6, 0.25, 7, 0.35, 0.1, 0.05, 0),
        "mid", new Profile(8, 11, 220, 45, 3.5, 0.12, 10, 0.7, 0.45, 0.25, 6),
        "tryhard", new Profile(10, 13, 170, 30, 2, 0.05, 14, 0.9, 0.8, 0.55, 8));

    public static Profile profile(String name) {
        Profile p = PROFILES.get(name == null ? "mid" : name);
        if (p == null) throw new IllegalArgumentException("profile must be rookie, mid or tryhard");
        return p;
    }

    /** A small seeded generator (mulberry32), the same sequence as human.js for the same numeric seed. */
    public static final class Rng {
        private int a;

        public Rng(long seed) {
            this.a = (int) seed;
        }

        public static Rng of(String seed) {
            int h = 0x811C9DC5;
            for (char c : seed.toCharArray()) h = (h ^ c) * 16777619;
            return new Rng(h);
        }

        public double next() {
            a += 0x6D2B79F5;
            int t = a;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }

        public double range(double lo, double hi) {
            return lo + next() * (hi - lo);
        }

        public boolean chance(double p) {
            return next() < p;
        }

        public double normal(double mean, double sd) {
            double u = Math.max(next(), 1e-9), v = next();
            return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }
    }

    public static double reactionMs(Rng r, Profile p) {
        return Math.max(110, r.normal(p.reactionMean(), p.reactionSd()));
    }

    /** Delay before the next swing: 1.8 spams at a drifting CPS; 1.9+ waits for the cooldown plus a human delay. */
    public static double nextSwingMs(Rng r, Profile p, String style, double cooldownMs) {
        if ("1.8".equals(style)) {
            double cps = r.range(p.cpsLo(), p.cpsHi());
            return Math.max(55, r.normal(1000 / cps, 1000 / cps / 6));
        }
        return cooldownMs + Math.max(0, r.normal(p.reactionMean() * 0.35, p.reactionSd() * 0.5));
    }

    /** Degrees into [-180, 180). */
    public static double wrap(double deg) {
        double d = deg % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** Minecraft yaw and pitch (degrees) to look from one point at another. */
    public static double[] angles(double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new double[] {wrap(yaw), Math.max(-90, Math.min(90, pitch))};
    }

    /** Where to put the crosshair: chest height plus a wander that grows with distance and the profile's aim. */
    public static double[] aimPoint(Rng r, Profile p, double x, double y, double z, double distance) {
        double spread = Math.tan(Math.toRadians(p.aim())) * Math.max(1, distance);
        return new double[] {x + r.normal(0, spread / 2), y + 1.2 + r.normal(0, spread / 3), z + r.normal(0, spread / 2)};
    }

    /**
     * The head as a slightly under-damped spring per axis, chasing a lagged copy of the target: a turn starts slow,
     * peaks mid-way and eases out with a small overshoot and a faint tremor. Degrees.
     */
    public static final class Gaze {
        private final Profile p;
        private double yaw, pitch, vy, vp, ty, tp, t;

        public Gaze(Rng r, Profile p, double yaw, double pitch) {
            this.p = p;
            this.yaw = yaw;
            this.pitch = pitch;
            this.ty = yaw;
            this.tp = pitch;
            this.t = r.range(0, 100);
        }

        /** Advances {@code dtSeconds} toward the target; returns {yaw, pitch}. */
        public double[] step(double dtSeconds, double targetYaw, double targetPitch) {
            double w = p.turn(), z = 0.7;
            int n = 8;
            double h = dtSeconds / n;
            for (int i = 0; i < n; i++) {
                double k = 1 - Math.exp(-h * w * 1.5);
                ty = wrap(ty + wrap(targetYaw - ty) * k);
                tp += (targetPitch - tp) * k;
                vy += (w * w * wrap(ty - yaw) - 2 * z * w * vy) * h;
                vp += (w * w * (tp - pitch) - 2 * z * w * vp) * h;
                yaw = wrap(yaw + vy * h);
                pitch = Math.max(-90, Math.min(90, pitch + vp * h));
            }
            t += dtSeconds;
            double tremor = 0.18;
            return new double[] {wrap(yaw + tremor * Math.sin(t * 9.3)), pitch + tremor * Math.sin(t * 7.1 + 1)};
        }
    }
}
