package dev.mccli.video;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewTest {
    /** A 4x2 RGBA frame, rows bottom-up as read back from the GPU: the bottom row is red, the top row is blue. */
    private static ByteBuffer frame() {
        ByteBuffer b = ByteBuffer.allocate(4 * 2 * 4);
        for (int x = 0; x < 4; x++) b.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
        for (int x = 0; x < 4; x++) b.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);
        return b.flip();
    }

    @Test
    void theFrameIsShrunkToRgbTopRowFirst() {
        byte[] rgb = Preview.shrink(frame(), 4, 2, new Preview.Size(2, 2));
        assertEquals(2 * 2 * 3, rgb.length);
        assertEquals(0, rgb[0] & 0xff);   // top-left is the top row: blue
        assertEquals(255, rgb[2] & 0xff);
        assertEquals(255, rgb[6] & 0xff); // bottom-left is red
        assertEquals(0, rgb[8] & 0xff);
    }

    @Test
    void theSizeKeepsTheAspectWithEvenSidesAndNeverGrows() {
        assertEquals(new Preview.Size(540, 960), Preview.size(1080, 1920, 540));
        assertEquals(new Preview.Size(640, 360), Preview.size(3840, 2160, 640));
        assertEquals(new Preview.Size(320, 180), Preview.size(320, 180, 1280));
    }

    @Test
    void ffmpegReadsSmallRgbFramesAndWritesMjpegParts() {
        List<String> a = Preview.ffmpeg("ffmpeg", new Preview.Size(540, 960), 12);
        assertTrue(a.containsAll(List.of("-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "540x960", "-framerate", "12", "mpjpeg")), a.toString());
        assertEquals("-", a.get(a.size() - 1));
    }

    @Test
    void framesAreTakenNoMoreOftenThanAsked() {
        Preview.Clock c = new Preview.Clock(10);
        assertTrue(c.due(0));
        assertFalse(c.due(50_000_000L));
        assertTrue(c.due(100_000_000L));
        assertFalse(c.due(150_000_000L));
    }
}
