package dev.mccli.video;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegArgsTest {
    private static VideoSettings settings(String json) {
        return VideoSettings.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void movKeepsAlphaAsProRes4444() {
        List<String> a = FfmpegArgs.video("ffmpeg", 1920, 1080, settings("{}"), Path.of("/t/v.mov"), "mov");
        assertTrue(a.containsAll(List.of("prores_ks", "4444", "yuva444p10le")), a.toString());
        assertFalse(a.contains("+faststart"));
        assertEquals("prores_ks", FfmpegArgs.encoderFor("mov", settings("{}")));
    }

    @Test
    void webmKeepsAlphaAsVp9AndTakesOpusSound() {
        List<String> a = FfmpegArgs.video("ffmpeg", 1920, 1080, settings("{}"), Path.of("/t/v.webm"), "webm");
        assertTrue(a.containsAll(List.of("libvpx-vp9", "yuva420p")), a.toString());
        List<String> m = FfmpegArgs.mux("ffmpeg", Path.of("/t/v.webm"), Path.of("/t/a.wav"), 160, Path.of("/t/o.webm"), "webm");
        assertTrue(m.contains("libopus"));
        assertFalse(m.contains("aac"));
    }

    @Test
    void mp4StaysH264WithAac() {
        List<String> a = FfmpegArgs.video("ffmpeg", 1080, 1920, settings("{}"), Path.of("/t/v.mp4"), "mp4");
        assertTrue(a.containsAll(List.of("libx264", "yuv420p", "+faststart")));
        assertNull(FfmpegArgs.encoderFor("mp4", settings("{}")));
        assertTrue(FfmpegArgs.mux("ffmpeg", Path.of("a"), Path.of("b"), 192, Path.of("c.mp4"), "mp4").contains("aac"));
    }

    @Test
    void priorityPicksTheFrameRateUnlessFpsIsGiven() {
        assertEquals(60, settings("{\"priority\":\"smooth\"}").fps());
        assertEquals(30, settings("{\"priority\":\"light\",\"preset\":\"max\"}").fps());
        assertEquals(24, settings("{\"priority\":\"smooth\",\"fps\":24}").fps());
        assertThrows(IllegalArgumentException.class, () -> settings("{\"priority\":\"fast\"}"));
    }
}
