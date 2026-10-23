package dev.mccli.video;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderModeTest {
    private static RenderMode parse(String json) {
        return RenderMode.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void offUnlessRenderIsTrue() {
        assertNull(parse("{}"));
        assertNull(parse("{\"render\":false}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"motion_blur\":4}"));
    }

    @Test
    void motionBlurTakesSamplesAndShutter() {
        RenderMode r = parse("{\"render\":true,\"motion_blur\":8,\"shutter\":0.5}");
        assertEquals(8, r.samples());
        assertEquals(4, r.blurFrames());
        assertEquals(480, r.rate(60));
        RenderMode plain = parse("{\"render\":true}");
        assertEquals(1, plain.samples());
        assertEquals(1, plain.blurFrames());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"render\":true,\"motion_blur\":40}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"render\":true,\"motion_blur\":4,\"shutter\":1.5}"));
    }

    @Test
    void frameTimesStepExactlyAndTheTotalMatchesTheDuration() {
        RenderMode r = parse("{\"render\":true,\"motion_blur\":4}");
        assertEquals(0, r.nanosAt(0, 30));
        assertEquals(Math.round(1e9 / 120), r.nanosAt(1, 30));
        assertEquals(1_000_000_000L, r.nanosAt(120, 30));
        assertEquals(240, r.totalFrames(2000, 30));
    }

    @Test
    void midFrameTimesNeverSkipOrRepeatAPacedFrame() {
        RenderMode r = parse("{\"render\":true,\"motion_blur\":7}");
        FramePacer pacer = new FramePacer(r.rate(60));
        for (long k = 0; k < 2000; k++) assertEquals(1, pacer.framesFor(r.pacedNanos(k, 60)), "frame " + k);
        assertEquals(0, pacer.padTo(r.nanosAt(2000, 60)));
    }

    @Test
    void blurAveragesSubFramesThenKeepsOneInEach() {
        RenderMode r = parse("{\"render\":true,\"motion_blur\":8,\"shutter\":0.5}");
        VideoSettings s = VideoSettings.parse(JsonParser.parseString("{\"fps\":60}").getAsJsonObject());
        List<String> a = FfmpegArgs.video("ffmpeg", 1920, 1080, s, r, Path.of("/t/v.mp4"), "mp4");
        assertEquals("480", a.get(a.indexOf("-framerate") + 1));
        String vf = a.get(a.indexOf("-vf") + 1);
        assertTrue(vf.startsWith("vflip,tmix=frames=4,framestep=8"), vf);
        assertEquals("60", a.get(a.indexOf("-r") + 1));
        List<String> plain = FfmpegArgs.video("ffmpeg", 1920, 1080, s, null, Path.of("/t/v.mp4"), "mp4");
        assertFalse(plain.get(plain.indexOf("-vf") + 1).contains("tmix"));
    }
}
