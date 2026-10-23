package dev.mccli.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg command lines: raw frames on stdin to a video, and the video muxed with the recorded sound. MP4 is H.264 or
 * H.265 for the game frame; MOV (ProRes 4444) and WebM (VP9) keep the alpha of a transparent GUI layer.
 */
public final class FfmpegArgs {
    private FfmpegArgs() {}

    /** Frames arrive as RGBA rows bottom-up (as read back from the GPU), hence the vflip. */
    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out, String container) {
        int[] size = VideoSettings.outputSize(width, height, s.maxHeight());
        String filters = "vflip";
        if (size[1] != (height & ~1)) filters += ",scale=" + size[0] + ":" + size[1] + ":flags=lanczos";
        else if (size[0] != width || size[1] != height) filters += ",crop=" + size[0] + ":" + size[1] + ":0:0";
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(s.fps()), "-i", "-",
                "-vf", filters));
        switch (container) {
            case "mov" -> a.addAll(List.of("-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le",
                    "-alpha_bits", "8", "-vendor", "apl0", out.toString()));
            case "webm" -> a.addAll(List.of("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-crf", Integer.toString(s.crf()),
                    "-b:v", "0", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1", "-auto-alt-ref", "0", out.toString()));
            default -> {
                if (s.codec().equals("h265")) {
                    a.addAll(List.of("-c:v", "libx265", "-preset", s.speed(), "-crf", Integer.toString(s.crf()), "-tag:v", "hvc1", "-x265-params", "log-level=error"));
                } else {
                    a.addAll(List.of("-c:v", "libx264", "-preset", s.speed(), "-crf", Integer.toString(s.crf())));
                }
                a.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()));
            }
        }
        return a;
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out, String container) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-i", wav.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy"));
        if (container.equals("webm")) {
            a.addAll(List.of("-c:a", "libopus", "-b:a", audioKbps + "k"));
        } else {
            a.addAll(List.of("-c:a", "aac", "-b:a", audioKbps + "k", "-movflags", "+faststart"));
        }
        a.add(out.toString());
        return a;
    }

    /** The encoder a container needs, or null when every ffmpeg build has it. */
    public static String encoderFor(String container, VideoSettings s) {
        return switch (container) {
            case "mov" -> "prores_ks";
            case "webm" -> "libvpx-vp9";
            default -> s.codec().equals("h265") ? "libx265" : null;
        };
    }

    /** Whether this ffmpeg lists the encoder (ffmpeg -encoders). */
    public static boolean hasEncoder(Path ffmpeg, String encoder) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return out.lines().anyMatch(l -> l.trim().split("\\s+").length > 1 && l.trim().split("\\s+")[1].equals(encoder));
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
