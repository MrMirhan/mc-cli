package dev.mccli.video;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Minecraft 26.2: asynchronous GPU readback of the main render target for video frames (pooled buffers). */
public final class FrameReader {
    public static final FrameReader INSTANCE = new FrameReader();
    private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
    private long bufferSize;

    public void read(RenderTarget target, Consumer<ByteBuffer> done) {
        GpuTexture texture = target.getColorTexture();
        if (texture == null) throw new IllegalStateException("the frame has no colour texture");
        long size = (long) target.width * target.height * texture.getFormat().blockSize();
        if (size != bufferSize) {
            release();
            bufferSize = size;
        }
        GpuBuffer buffer = free.poll();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "MC-CLI video frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        }
        GpuBuffer b = buffer;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, b, 0, () -> {
            try (GpuBufferSlice.MappedView view = b.map(true, false)) {
                done.accept(view.data());
            } finally {
                if (b.size() == bufferSize) free.add(b);
                else b.close();
            }
        }, 0);
    }

    public void release() {
        for (GpuBuffer b : free) b.close();
        free.clear();
        bufferSize = 0;
    }
}
