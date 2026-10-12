package dev.mccli.video;

import com.google.gson.JsonObject;
import dev.mccli.camera.CameraMotion;

import java.nio.file.Path;
import java.util.Locale;

/** record start's parameters, validated. {@code durationMs} is the effective length (given, or the camera motion's). */
public record RecordParams(Path savePath, VideoSettings settings, boolean hud, Long durationMs, int maxSeconds,
                           CameraMotion motion, boolean waitForTerrain, boolean waitForEnd) {
    public static final int MAX_SECONDS = 600;

    public static RecordParams parse(JsonObject p, CameraMotion motion) {
        String save = VideoSettings.str(p, "path", null);
        if (save == null) throw new IllegalArgumentException("path is required (an absolute .mp4 file)");
        if (!save.toLowerCase(Locale.ROOT).endsWith(".mp4")) throw new IllegalArgumentException("path must end in .mp4");
        VideoSettings settings = VideoSettings.parse(p);
        int maxSeconds = p.has("max_seconds") ? p.get("max_seconds").getAsInt() : 120;
        if (maxSeconds < 1 || maxSeconds > MAX_SECONDS) throw new IllegalArgumentException("max_seconds must be 1-" + MAX_SECONDS);
        Long duration = p.has("duration_ms") ? Math.round(p.get("duration_ms").getAsDouble()) : motion == null ? null : motion.durationMs();
        if (duration != null && duration < 100) throw new IllegalArgumentException("duration_ms must be at least 100");
        if (duration != null && duration > maxSeconds * 1000L) {
            throw new IllegalArgumentException("duration_ms is longer than max_seconds (" + maxSeconds + " s); raise max_seconds (at most " + MAX_SECONDS + ")");
        }
        boolean wait = p.has("wait") && p.get("wait").getAsBoolean();
        if (wait && duration == null) throw new IllegalArgumentException("wait:true needs duration_ms or a camera motion, so the recording ends by itself");
        boolean hud = p.has("hud") && p.get("hud").getAsBoolean();
        boolean terrain = !p.has("wait_for_terrain") || p.get("wait_for_terrain").getAsBoolean();
        return new RecordParams(Path.of(save).toAbsolutePath(), settings, hud, duration, maxSeconds, motion, terrain, wait);
    }
}
