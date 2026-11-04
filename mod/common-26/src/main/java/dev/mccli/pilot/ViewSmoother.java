package dev.mccli.pilot;

/**
 * The pilot turns the head once per game tick (20 a second), but a recording runs at 30 or 60 frames a second, so a
 * turn shown as is stalls on every third frame (two of three at 60). This keeps the head angle at the start and at the
 * end of the last tick and gives each rendered frame its share of that turn, as a mouse does. Only the camera reads
 * it: the player's own angle, the server, the crosshair and the clicks stay on the tick values.
 */
public final class ViewSmoother {
    private volatile float[] turn;

    /** The head angle before and after this tick's step. */
    public void tick(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        turn = new float[] {fromYaw, fromPitch, toYaw, toPitch};
    }

    /** {yaw, pitch} for a frame `partial` (0 to 1) of the way into the next tick, or null when the pilot is idle. */
    public float[] at(float partial) {
        float[] t = turn;
        if (t == null) return null;
        float k = Math.max(0f, Math.min(1f, partial));
        float dy = wrap(t[2] - t[0]);
        return new float[] {t[0] + dy * k, t[1] + (t[3] - t[1]) * k};
    }

    public void clear() {
        turn = null;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
