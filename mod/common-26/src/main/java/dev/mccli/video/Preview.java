package dev.mccli.video;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mccli.McCliMod;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A small live preview of the game frame for a person who watches the camera: GET /live on the MCP port streams MJPEG
 * (multipart/x-mixed-replace) at a low size and frame rate. It reads the frame the game already drew, through the same
 * asynchronous GPU readback as {@link Recorder}, shrinks it here and lets ffmpeg encode the JPEGs, so it works in
 * headless mode, behind a lock screen and next to a recording. Nothing runs while nobody watches.
 */
public final class Preview {
    public static final Preview INSTANCE = new Preview();
    public static final int MAX_VIEWERS = 3;

    public record Size(int width, int height) {}

    /** Paces the readbacks: at most fps a second. */
    static final class Clock {
        private final long every;
        private long next = Long.MIN_VALUE;

        Clock(int fps) {
            every = 1_000_000_000L / Math.max(1, fps);
        }

        boolean due(long now) {
            if (next != Long.MIN_VALUE && now < next) return false;
            next = (next == Long.MIN_VALUE ? now : Math.max(next, now - every)) + every;
            return true;
        }
    }

    private final FrameReader reader = new FrameReader();
    private final List<OutputStream> viewers = new CopyOnWriteArrayList<>();
    private volatile Session session;
    private volatile boolean reading;

    private static final class Session {
        final Size size;
        final int fps;
        final Clock clock;
        final Process process;
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(2);
        int sourceWidth, sourceHeight;

        Session(Size size, int fps, int sourceWidth, int sourceHeight, Process process) {
            this.size = size;
            this.fps = fps;
            this.clock = new Clock(fps);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.process = process;
        }
    }

    /** The preview size for a frame: `width` wide at most, never larger than the frame, even sides, same aspect. */
    public static Size size(int sourceWidth, int sourceHeight, int width) {
        int w = Math.max(160, Math.min(1280, width));
        w = Math.min(w, sourceWidth);
        int h = (int) Math.round((double) sourceHeight * w / sourceWidth);
        return new Size(w & ~1, Math.max(2, h & ~1));
    }

    /** RGBA rows bottom-up (GPU readback) to RGB rows top-down at `size`, by sampling. */
    public static byte[] shrink(ByteBuffer rgba, int width, int height, Size size) {
        byte[] out = new byte[size.width() * size.height() * 3];
        int o = 0;
        for (int y = 0; y < size.height(); y++) {
            int sy = height - 1 - (int) ((long) y * height / size.height());
            int row = sy * width * 4;
            for (int x = 0; x < size.width(); x++) {
                int i = row + (int) ((long) x * width / size.width()) * 4;
                out[o++] = rgba.get(i);
                out[o++] = rgba.get(i + 1);
                out[o++] = rgba.get(i + 2);
            }
        }
        return out;
    }

    public static List<String> ffmpeg(String ffmpeg, Size size, int fps) {
        return new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", size.width() + "x" + size.height(), "-framerate", Integer.toString(fps),
                "-i", "-", "-q:v", "6", "-f", "mpjpeg", "-"));
    }

    /** Called on the render thread after each frame is drawn. */
    public void onFrame() {
        Session s = session;
        if (s == null || reading || !s.clock.due(System.nanoTime())) return;
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target.width != s.sourceWidth || target.height != s.sourceHeight) {
            // the frame changed size (a resize, a headless size change): the stream keeps its own size
            s.sourceWidth = target.width;
            s.sourceHeight = target.height;
        }
        int w = target.width, h = target.height;
        reading = true;
        try {
            reader.read(target, data -> {
                reading = false;
                s.frames.poll();
                s.frames.offer(shrink(data, w, h, s.size));
            });
        } catch (RuntimeException e) {
            reading = false;
            McCliMod.LOGGER.warn("MC-CLI preview: {}", e.getMessage());
        }
    }

    /**
     * Streams to `out` until the viewer leaves or the game stops. The first viewer sets the size and rate; later
     * viewers share that stream. Returns false when too many already watch.
     */
    public boolean serve(OutputStream out, int fps, int width) throws InterruptedException {
        synchronized (this) {
            if (viewers.size() >= MAX_VIEWERS) return false;
            if (session == null && !start(Math.max(1, Math.min(30, fps)), width)) return false;
            viewers.add(out);
        }
        CountDownLatch gone = new CountDownLatch(1);
        Thread watch = new Thread(() -> {
            while (viewers.contains(out) && session != null) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            gone.countDown();
        }, "MC-CLI-preview-viewer");
        watch.setDaemon(true);
        watch.start();
        gone.await();
        synchronized (this) {
            viewers.remove(out);
            if (viewers.isEmpty()) stop();
        }
        return true;
    }

    private boolean start(int fps, int width) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        Size size = size(target.width, target.height, width);
        Process process;
        try {
            process = new ProcessBuilder(ffmpeg(FfmpegLocator.require().toString(), size, fps)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | RuntimeException e) {
            McCliMod.LOGGER.warn("MC-CLI preview could not start ffmpeg: {}", e.getMessage());
            return false;
        }
        Session s = new Session(size, fps, target.width, target.height, process);
        Thread feed = new Thread(() -> {
            try (OutputStream in = process.getOutputStream()) {
                while (session == s) {
                    byte[] frame = s.frames.poll(1, TimeUnit.SECONDS);
                    if (frame != null && frame.length == s.size.width() * s.size.height() * 3) {
                        in.write(frame);
                        in.flush();
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // ffmpeg went away or the preview stopped
            }
        }, "MC-CLI-preview-feed");
        Thread fan = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            try (InputStream jpegs = process.getInputStream()) {
                int n;
                while ((n = jpegs.read(buf)) > 0) {
                    for (OutputStream v : viewers) {
                        try {
                            v.write(buf, 0, n);
                            v.flush();
                        } catch (IOException e) {
                            viewers.remove(v);
                        }
                    }
                }
            } catch (IOException ignored) {
                // stopped
            }
            synchronized (this) {
                if (session == s) stop();
            }
        }, "MC-CLI-preview-out");
        feed.setDaemon(true);
        fan.setDaemon(true);
        session = s;
        feed.start();
        fan.start();
        return true;
    }

    private void stop() {
        Session s = session;
        session = null;
        if (s != null) s.process.destroy();
    }
}
