package dev.mccli.camera;

/** A camera move over time (camera path / orbit). Pure: the pose depends only on the elapsed time. */
public interface CameraMotion {
    /** The pose {@code ms} milliseconds after the start; before 0 and after the end it holds the end poses. */
    CameraOverride.Pose at(double ms);

    long durationMs();

    /** "path", "orbit", "follow" or "attach", as the camera tool names it. */
    String kind();

    /** The field of view in degrees at {@code ms}, or null to keep the game's own. */
    default Double fovAt(double ms) {
        return null;
    }

    /** Smoothstep: zero speed at both ends, the middle stays in the middle. */
    static double easeInOut(double f) {
        return f * f * (3 - 2 * f);
    }

    static double clamp01(double f) {
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }
}
