package dev.mccli.video;

import com.google.gson.JsonObject;
import dev.mccli.camera.CameraMotion;
import dev.mccli.capture.GuiCapture;

import java.nio.file.Path;
import java.util.Locale;

/**
 * record start's parameters, validated. {@code durationMs} is the effective length (given, or the camera motion's).
 * {@code gui} is set for a transparent layer (.mov or .webm), null for the game frame (.mp4). {@code render} is set
 * for a frame-exact recording, which has no sound.
 */
public record RecordParams(Path savePath, VideoSettings settings, boolean hud, Long durationMs, int maxSeconds,
                           CameraMotion motion, boolean waitForTerrain, boolean waitForEnd, GuiCapture.Options gui,
                           RenderMode render) {
    public static final int MAX_SECONDS = 600;

    public static RecordParams parse(JsonObject p, CameraMotion motion) {
        String save = VideoSettings.str(p, "path", null);
        if (save == null) throw new IllegalArgumentException("path is required (an absolute .mp4 file)");
        GuiCapture.Options gui = GuiCapture.Options.parse(p, save, true);
        if (gui == null && !save.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("path must end in .mp4 (.mov or .webm only with a transparent layer)");
        }
        VideoSettings settings = VideoSettings.parse(p);
        int maxSeconds = p.has("max_seconds") ? p.get("max_seconds").getAsInt() : 120;
        if (maxSeconds < 1 || maxSeconds > MAX_SECONDS) throw new IllegalArgumentException("max_seconds must be 1-" + MAX_SECONDS);
        Long duration = p.has("duration_ms") ? Math.round(p.get("duration_ms").getAsDouble()) : motion == null || motion.durationMs() <= 0 ? null : motion.durationMs();
        if (duration != null && duration < 100) throw new IllegalArgumentException("duration_ms must be at least 100");
        if (duration != null && duration > maxSeconds * 1000L) {
            throw new IllegalArgumentException("duration_ms is longer than max_seconds (" + maxSeconds + " s); raise max_seconds (at most " + MAX_SECONDS + ")");
        }
        boolean wait = p.has("wait") && p.get("wait").getAsBoolean();
        if (wait && duration == null) throw new IllegalArgumentException("wait:true needs duration_ms or a camera motion, so the recording ends by itself");
        RenderMode render = RenderMode.parse(p);
        if (render != null) {
            if (duration == null) throw new IllegalArgumentException("render:true needs duration_ms or a camera motion, so the recording knows its length");
            if (p.has("audio") && p.get("audio").getAsBoolean()) {
                throw new IllegalArgumentException("render:true records no sound (the game runs slower than the video); add the music in the edit");
            }
            settings = new VideoSettings(settings.preset(), settings.fps(), settings.maxHeight(), settings.codec(), settings.crf(),
                settings.speed(), false, settings.audioKbps());
        }
        boolean hud = p.has("hud") && p.get("hud").getAsBoolean();
        boolean terrain = gui == null && (!p.has("wait_for_terrain") || p.get("wait_for_terrain").getAsBoolean());
        return new RecordParams(Path.of(save).toAbsolutePath(), settings, hud, duration, maxSeconds, motion, terrain, wait, gui, render);
    }

    /** The container's extension: mp4, mov or webm. */
    public String container() {
        String n = savePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.substring(n.lastIndexOf('.') + 1);
    }
}
