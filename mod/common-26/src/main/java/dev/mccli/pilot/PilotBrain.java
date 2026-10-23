package dev.mccli.pilot;

import dev.mccli.pilot.HumanModel.Gaze;
import dev.mccli.pilot.HumanModel.Profile;
import dev.mccli.pilot.HumanModel.Rng;

/**
 * One decision per game tick, with no Minecraft types: where to look, which movement keys to hold and whether to
 * swing. Fight logic follows Video Atölyesi's actors.js: reaction delay, wandering aim, strafes, w-taps, crits and
 * backing off at low health; "1.8" spams at the profile's CPS, "1.9" waits for the attack cooldown.
 */
public final class PilotBrain {
    public enum Mode { FIGHT, FOLLOW, WALK, LOOK }

    /** What the body knows this tick. Positions are feet; {@code eyeY} is the eye height. */
    public record Sense(long nowMs, double x, double y, double eyeY, double z, double yaw, double pitch, double health,
                        boolean onGround, boolean blocked, Double tx, Double ty, Double tz) {}

    /** What to do this tick. */
    public record Act(double yaw, double pitch, boolean forward, boolean back, boolean left, boolean right,
                      boolean sprint, boolean jump, boolean attack, boolean swingOnly, boolean done) {}

    private final Mode mode;
    private final Profile p;
    private final Rng r;
    private final String style;
    private final double keepDistance;
    private final long untilMs;
    private Gaze gaze;
    private long startAt = -1, swingAt, aimUntil, strafeUntil, retreatUntil = -10_000, wtapUntil, jumpUntil, critUntil;
    private double[] aim, aimBase;
    private int strafe;
    private boolean critPending;
    private long lastMs;
    private int hits;

    public PilotBrain(Mode mode, String profile, String style, double keepDistance, long untilMs, String seed) {
        this.mode = mode;
        this.p = HumanModel.profile(profile);
        this.r = Rng.of(seed == null ? "pilot" : seed);
        this.style = style == null ? "1.8" : style;
        if (!"1.8".equals(this.style) && !"1.9".equals(this.style)) throw new IllegalArgumentException("style must be 1.8 or 1.9");
        this.keepDistance = keepDistance;
        this.untilMs = untilMs;
    }

    public int hits() {
        return hits;
    }

    public Act tick(Sense s) {
        if (gaze == null) {
            gaze = new Gaze(r, p, s.yaw(), s.pitch());
            startAt = s.nowMs() + (long) HumanModel.reactionMs(r, p);
            lastMs = s.nowMs();
        }
        double dt = Math.max(0.001, (s.nowMs() - lastMs) / 1000.0);
        lastMs = s.nowMs();
        boolean timeUp = untilMs > 0 && s.nowMs() >= untilMs;
        if (s.tx() == null) {
            double[] look = gaze.step(dt, s.yaw(), s.pitch());
            return new Act(look[0], look[1], false, false, false, false, false, false, false, false, timeUp);
        }
        double dx = s.tx() - s.x(), dz = s.tz() - s.z();
        double flat = Math.hypot(dx, dz);
        double d = Math.sqrt(dx * dx + dz * dz + (s.ty() - s.y()) * (s.ty() - s.y()));
        long now = s.nowMs();

        double[] want;
        if (mode == Mode.FIGHT) {
            if (aim == null || now > aimUntil) {
                aim = HumanModel.aimPoint(r, p, s.tx(), s.ty(), s.tz(), d);
                aimBase = new double[] {s.tx(), s.ty(), s.tz()};
                aimUntil = now + (long) r.range(180, 380);
            }
            want = HumanModel.angles(s.x(), s.eyeY(), s.z(), aim[0] + s.tx() - aimBase[0], aim[1] + s.ty() - aimBase[1],
                aim[2] + s.tz() - aimBase[2]);
        } else if (mode == Mode.LOOK) {
            want = HumanModel.angles(s.x(), s.eyeY(), s.z(), s.tx(), s.ty(), s.tz());
        } else {
            want = HumanModel.angles(s.x(), s.eyeY(), s.z(), s.tx(), s.eyeY(), s.tz());
        }
        double[] look = gaze.step(dt, want[0], want[1]);
        boolean facing = Math.abs(HumanModel.wrap(want[0] - look[0])) < 70;
        if (now < startAt || timeUp) {
            return new Act(look[0], look[1], false, false, false, false, false, false, false, false, timeUp);
        }

        return switch (mode) {
            case LOOK -> new Act(look[0], look[1], false, false, false, false, false, false, false, false, false);
            case WALK, FOLLOW -> {
                boolean arrived = flat <= (mode == Mode.WALK ? 0.6 : keepDistance);
                boolean go = !arrived && facing;
                boolean jump = go && s.onGround() && s.blocked();
                yield new Act(look[0], look[1], go, false, false, false, go && flat > 3, jump, false, false,
                    mode == Mode.WALK && arrived);
            }
            case FIGHT -> fight(s, look, d, facing, now);
        };
    }

    private Act fight(Sense s, double[] look, double d, boolean facing, long now) {
        boolean forward, back, left, right, sprint, jump = false, attack = false, swingOnly = false;
        if (p.retreatAt() > 0 && s.health() <= p.retreatAt() && now > retreatUntil + 4000) {
            retreatUntil = now + (long) r.range(900, 1600);
        }
        if (now < retreatUntil) {
            forward = false;
            sprint = false;
            back = true;
            left = right = false;
        } else {
            back = d < 1.4;
            forward = d > 2.4 && now > wtapUntil && facing;
            sprint = forward;
            if (now > strafeUntil) {
                strafe = r.chance(p.strafe()) ? (r.chance(0.5) ? 1 : -1) : 0;
                strafeUntil = now + (long) r.range(250, 700);
            }
            left = d < 4 && strafe < 0;
            right = d < 4 && strafe > 0;
        }
        if (now < critUntil) {
            jump = true;
        } else if (d < 3.1 && now >= swingAt && facing) {
            if (!critPending && !"1.8".equals(style) && s.onGround() && r.chance(p.crit())) {
                critPending = true;
                // jump first and swing on the way down, like a crit
                critUntil = now + (long) r.range(300, 360);
                swingAt = critUntil;
                jump = true;
            } else {
                critPending = false;
                if (r.chance(p.miss())) {
                    swingOnly = true;
                } else {
                    attack = true;
                    hits++;
                    if (r.chance(p.wtap())) wtapUntil = now + (long) r.range(70, 140);
                }
                swingAt = now + (long) HumanModel.nextSwingMs(r, p, style, 625);
            }
        }
        if (now < jumpUntil) {
            jump = true;
        } else if (d < 4 && s.onGround() && r.chance(0.012)) {
            jumpUntil = now + 120;
            jump = true;
        }
        if (s.blocked() && forward && s.onGround()) jump = true;
        return new Act(look[0], look[1], forward, back, left, right, sprint, jump, attack, swingOnly, false);
    }
}
