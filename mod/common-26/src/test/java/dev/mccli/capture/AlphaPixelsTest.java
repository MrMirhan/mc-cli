package dev.mccli.capture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class AlphaPixelsTest {
    @Test
    void halfTransparentRedBecomesFullRedAtHalfAlpha() {
        byte[] px = {(byte) 128, 0, 0, (byte) 128};
        AlphaPixels.unpremultiply(px);
        assertEquals(255, px[0] & 255);
        assertEquals(128, px[3] & 255);
    }

    @Test
    void clearPixelsHaveNoColour() {
        byte[] px = {10, 20, 30, 0};
        AlphaPixels.unpremultiply(px);
        assertArrayEquals(new byte[4], px);
    }

    @Test
    void rowsAreFlippedTopDown() {
        // 1x2: bottom row (first in the buffer) red, top row green, both opaque
        ByteBuffer gpu = ByteBuffer.wrap(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255});
        byte[] top = AlphaPixels.topDownStraight(gpu, 1, 2);
        assertEquals(0xFF00FF00, AlphaPixels.argb(top, 1, 0, 0));
        assertEquals(0xFFFF0000, AlphaPixels.argb(top, 1, 0, 1));
    }

    @Test
    void boundsCoverDrawnPixelsPlusPadding() {
        int w = 20, h = 10;
        byte[] img = new byte[w * h * 4];
        img[(4 * w + 6) * 4 + 3] = (byte) 255;
        img[(5 * w + 9) * 4 + 3] = 1;
        assertArrayEquals(new int[] {4, 2, 8, 6}, AlphaPixels.bounds(img, w, h, 2));
        assertNull(AlphaPixels.bounds(new byte[w * h * 4], w, h, 8));
        assertArrayEquals(new int[] {0, 0, 20, 10}, AlphaPixels.bounds(img, w, h, 50));
    }

    @Test
    void layerNames() {
        assertEquals(GuiLayer.GUI_TOOLTIP, GuiLayer.parse("gui+tooltip"));
        assertEquals(GuiLayer.WORLD, GuiLayer.parse(null));
        assertThrows(IllegalArgumentException.class, () -> GuiLayer.parse("hud"));
    }
}
