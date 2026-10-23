package dev.mccli.commands;

import com.google.gson.JsonObject;
import dev.mccli.camera.CameraMotionJson;
import dev.mccli.util.MainThreadExecutor;
import dev.mccli.util.TerrainWait;
import dev.mccli.video.FfmpegArgs;
import dev.mccli.video.FfmpegLocator;
import dev.mccli.video.RecordParams;
import dev.mccli.video.Recorder;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Real-time video of the game frame and its sound, encoded by ffmpeg on this computer.
 *
 * Params:
 * - action: start | stop | status
 * start:
 * - path: absolute .mp4 path (required); .mov (ProRes 4444) or .webm (VP9) with a transparent layer
 * - preset: max | high (default) | balanced | light; fps, resolution, codec (h264|h265), crf, speed, audio, audioBitrate
 * - priority: smooth (60 fps) | light (30 fps), when fps is not given
 * - layer: world (default) | gui | tooltip | gui+tooltip: the open screen and/or its tooltip with alpha, full
 *   window size; gui_scale 1-8 for this recording only. No camera with a transparent layer.
 * - hud: keep the HUD in the video (default false)
 * - camera: a camera motion, see the camera command (orbit / path); it starts with the first frame
 * - duration_ms: stop after this long (default: the camera motion's length); max_seconds: hard limit (default 120)
 * - wait: return when the video is written (needs duration_ms or camera)
 * - wait_for_terrain: wait until the view is built before the first frame (default true)
 * - render: true steps the camera clock one frame per captured frame (no sound, needs a length);
 *   motion_blur 1-16 sub-frames per frame, shutter 0-1 (default 0.5)
 *
 * stop / a wait:true start return: path, durationMs, fps, width, height, frames, droppedFrames, duplicatedFrames, audio, sizeBytes.
 */
public class RecordCommand implements Command {
    @Override
    public String getName() {
        return "record";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "status";
        return switch (action) {
            case "status" -> MainThreadExecutor.submit(Recorder.INSTANCE::status);
            case "stop" -> MainThreadExecutor.submit(Recorder.INSTANCE::stop).thenCompose(f -> f);
            case "start" -> start(params);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("action must be start, stop or status"));
        };
    }

    private CompletableFuture<JsonObject> start(JsonObject p) {
        JsonObject camera = p.has("camera") && p.get("camera").isJsonObject() ? p.getAsJsonObject("camera") : null;
        RecordParams checked;
        try {
            checked = RecordParams.parse(p, camera == null ? null : CameraMotionJson.parse(camera, null));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path ffmpeg = FfmpegLocator.require();
                String encoder = FfmpegArgs.encoderFor(checked.container(), checked.settings());
                if (encoder != null && !FfmpegArgs.hasEncoder(ffmpeg, encoder)) {
                    throw new IllegalStateException("FFMPEG_FAILED: this ffmpeg has no " + encoder + " encoder ("
                        + ffmpeg + "); use the other format or a full ffmpeg build");
                }
                RecordParams params = MainThreadExecutor.submit(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) throw new IllegalStateException("Player not in game");
                    RecordParams rp = RecordParams.parse(p, camera == null ? null : CameraMotionJson.parse(camera, CameraCommand.currentPose(mc)));
                    Recorder.INSTANCE.prepare(rp, ffmpeg);
                    return rp;
                }).get();
                JsonObject started;
                try {
                    if (params.waitForTerrain()) TerrainWait.sectionsBuilt(5000);
                    started = MainThreadExecutor.submit(Recorder.INSTANCE::begin).get();
                } catch (Exception e) {
                    MainThreadExecutor.submitVoid(Recorder.INSTANCE::abort).get();
                    throw e;
                }
                if (!params.waitForEnd()) return started;
                return MainThreadExecutor.submit(Recorder.INSTANCE::done).get().get();
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                throw cause instanceof RuntimeException r ? r : new RuntimeException(cause.getMessage(), cause);
            }
        });
    }
}
