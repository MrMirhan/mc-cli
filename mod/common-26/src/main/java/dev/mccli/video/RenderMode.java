package dev.mccli.video;

import com.google.gson.JsonObject;

/**
 * Frame-exact recording: the camera clock steps 1/rate per captured frame instead of following the wall clock, so
 * every frame is on time however slowly the game renders. With motion blur each output frame is the average of
 * {@code blurFrames} of its {@code samples} sub-frames (shutter = the open fraction of the frame).
 */
public record RenderMode(int samples, double shutter) {
    public static final int MAX_SAMPLES = 16;

    /** null unless {@code render:true}. */
    public static RenderMode parse(JsonObject p) {
        boolean render = p.has("render") && p.get("render").getAsBoolean();
        if (!render) {
            if (p.has("motion_blur")) throw new IllegalArgumentException("motion_blur needs render:true");
            return null;
        }
        int samples = p.has("motion_blur") ? p.get("motion_blur").getAsInt() : 1;
        if (samples < 1 || samples > MAX_SAMPLES) throw new IllegalArgumentException("motion_blur must be 1-" + MAX_SAMPLES + " sub-frames");
        double shutter = p.has("shutter") ? p.get("shutter").getAsDouble() : 0.5;
        if (shutter <= 0 || shutter > 1) throw new IllegalArgumentException("shutter must be above 0 and at most 1 (0.5 = 180 degrees)");
        return new RenderMode(samples, shutter);
    }

    public int blurFrames() {
        return Math.max(1, (int) Math.round(samples * shutter));
    }

    /** Captured frames per second of video time. */
    public int rate(int fps) {
        return fps * samples;
    }

    /** Video time of captured frame {@code k}. */
    public long nanosAt(long k, int fps) {
        return Math.round(k * 1e9 / rate(fps));
    }

    /** Halfway through frame {@code k}: a time the real-time pacer counts as exactly one new frame. */
    public long pacedNanos(long k, int fps) {
        return Math.round((k + 0.5) * 1e9 / rate(fps));
    }

    public long totalFrames(long durationMs, int fps) {
        return Math.max(1, Math.round(durationMs * (double) rate(fps) / 1000));
    }
}
