package dev.mccli.capture;

import java.nio.ByteBuffer;

/**
 * Pixels of the GUI canvas as read back from the GPU: RGBA, rows bottom-up, premultiplied colour (the GUI blends
 * colour with SRC_ALPHA and alpha with ONE, onto a canvas cleared to 0). Images and videos want straight alpha.
 */
public final class AlphaPixels {
    private AlphaPixels() {}

    /** Premultiplied to straight alpha, in place. */
    public static void unpremultiply(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            int a = rgba[i + 3] & 255;
            if (a == 0) {
                rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
            } else if (a < 255) {
                for (int c = 0; c < 3; c++) {
                    rgba[i + c] = (byte) Math.min(255, ((rgba[i + c] & 255) * 255 + a / 2) / a);
                }
            }
        }
    }

    /** A copy of a read-back buffer: straight alpha, rows top-down. */
    public static byte[] topDownStraight(ByteBuffer rgba, int width, int height) {
        byte[] out = new byte[width * height * 4];
        int row = width * 4;
        for (int y = 0; y < height; y++) {
            rgba.get((height - 1 - y) * row, out, y * row, row);
        }
        unpremultiply(out);
        return out;
    }

    /** {x, y, width, height} of the pixels with any alpha, grown by {@code pad} and clamped; null when nothing is drawn. */
    public static int[] bounds(byte[] topDown, int width, int height, int pad) {
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (topDown[(y * width + x) * 4 + 3] != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad);
        int x1 = Math.min(width - 1, maxX + pad), y1 = Math.min(height - 1, maxY + pad);
        return new int[] {x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    /** Pixel (x, y) of a top-down RGBA buffer as ARGB. */
    public static int argb(byte[] topDown, int width, int x, int y) {
        int i = (y * width + x) * 4;
        return (topDown[i + 3] & 255) << 24 | (topDown[i] & 255) << 16 | (topDown[i + 1] & 255) << 8 | (topDown[i + 2] & 255);
    }
}
