package dev.mccli.video;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** Encoder settings for one recording: a preset, with any of its values overridden. */
public record VideoSettings(String preset, int fps, Integer maxHeight, String codec, int crf, String speed, boolean audio, int audioKbps) {
    private record Preset(int crf, String speed, int fps, Integer maxHeight, int audioKbps) {}

    // From best and heaviest to smallest and lightest.
    private static final Map<String, Preset> PRESETS = Map.of(
            "max", new Preset(16, "slow", 60, null, 256),
            "high", new Preset(20, "medium", 30, null, 192),
            "balanced", new Preset(23, "veryfast", 30, null, 160),
            "light", new Preset(28, "veryfast", 30, 720, 96));
    static final List<String> SPEEDS = List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow");
    private static final Map<String, Integer> RESOLUTIONS = Map.of("2160p", 2160, "1440p", 1440, "1080p", 1080, "720p", 720, "480p", 480);

    public static VideoSettings parse(JsonObject p) {
        String name = str(p, "preset", "high");
        Preset preset = PRESETS.get(name);
        if (preset == null) throw new IllegalArgumentException("preset must be max, high, balanced or light, not \"" + name + "\"");
        int fps = p.has("fps") ? p.get("fps").getAsInt() : preset.fps();
        if (fps < 10 || fps > 120) throw new IllegalArgumentException("fps must be 10-120");
        Integer maxHeight = preset.maxHeight();
        String res = str(p, "resolution", null);
        if (res != null) {
            if (res.equals("source")) maxHeight = null;
            else if (RESOLUTIONS.containsKey(res)) maxHeight = RESOLUTIONS.get(res);
            else throw new IllegalArgumentException("resolution must be source, 2160p, 1440p, 1080p, 720p or 480p");
        }
        String codec = str(p, "codec", "h264");
        if (!codec.equals("h264") && !codec.equals("h265")) throw new IllegalArgumentException("codec must be h264 or h265");
        int crf = p.has("crf") ? p.get("crf").getAsInt() : preset.crf();
        if (crf < 0 || crf > 51) throw new IllegalArgumentException("crf must be 0-51 (lower is better quality and a bigger file)");
        String speed = str(p, "speed", preset.speed());
        if (!SPEEDS.contains(speed)) throw new IllegalArgumentException("speed must be one of " + String.join(", ", SPEEDS));
        boolean audio = !p.has("audio") || p.get("audio").getAsBoolean();
        int kbps = p.has("audioBitrate") ? p.get("audioBitrate").getAsInt() : preset.audioKbps();
        if (kbps < 64 || kbps > 320) throw new IllegalArgumentException("audioBitrate must be 64-320 (kbit/s)");
        return new VideoSettings(name, fps, maxHeight, codec, crf, speed, audio, kbps);
    }

    /**
     * The encoded size for a {@code w}×{@code h} game frame: scaled down to {@code maxHeight} (never up) keeping
     * the aspect ratio, and even in both directions as yuv420p needs.
     */
    public static int[] outputSize(int w, int h, Integer maxHeight) {
        if (maxHeight != null && h > maxHeight) {
            double scale = maxHeight / (double) h;
            return new int[] {(int) Math.round(w * scale / 2) * 2, maxHeight & ~1};
        }
        return new int[] {w & ~1, h & ~1};
    }

    static String str(JsonObject p, String key, String fallback) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsString() : fallback;
    }
}
