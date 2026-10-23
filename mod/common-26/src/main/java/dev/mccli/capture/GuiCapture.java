package dev.mccli.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mccli.util.MainThreadExecutor;
import dev.mccli.video.FrameReader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Screenshots of the open screen and/or its tooltip on a transparent background, as PNG with alpha. */
public final class GuiCapture {
    private static final FrameReader READER = new FrameReader();
    private static final int TRIM_PAD = 8;

    public record Options(GuiLayer layer, boolean split, boolean trim, Integer guiScale) {
        /** The transparent-layer part of screenshot or record params; null for the normal game frame. */
        public static Options parse(JsonObject p, String path, boolean video) {
            GuiLayer layer = GuiLayer.parse(p.has("layer") ? p.get("layer").getAsString() : null);
            boolean split = p.has("split") && p.get("split").getAsBoolean();
            if (!layer.transparent()) {
                if (split) throw new IllegalArgumentException("split needs layer gui+tooltip");
                return null;
            }
            String name = path.toLowerCase(Locale.ROOT);
            if (video ? !(name.endsWith(".mov") || name.endsWith(".webm")) : !name.endsWith(".png")) {
                throw new IllegalArgumentException(video
                    ? "a transparent layer needs a .mov (ProRes 4444) or .webm (VP9) path: MP4 has no transparency"
                    : "a transparent layer needs a .png path: JPEG has no transparency");
            }
            if (split && layer != GuiLayer.GUI_TOOLTIP) throw new IllegalArgumentException("split needs layer gui+tooltip");
            if (p.has("camera")) throw new IllegalArgumentException("camera does not apply to a transparent layer: it has no world");
            Integer scale = p.has("gui_scale") ? Integer.valueOf(p.get("gui_scale").getAsInt()) : null;
            if (scale != null && (scale < 1 || scale > 8)) throw new IllegalArgumentException("gui_scale must be 1-8");
            boolean trim = !p.has("trim") || p.get("trim").getAsBoolean();
            return new Options(layer, split, trim && !video, scale);
        }
    }

    private GuiCapture() {}

    /** {@code <name>.gui.png} next to {@code <name>.png}. */
    public static Path splitPath(Path path, String part) {
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return path.resolveSibling(dot < 0 ? n + "." + part : n.substring(0, dot) + "." + part + n.substring(dot));
    }

    public static CompletableFuture<JsonObject> screenshot(Path path, Options o) {
        List<GuiLayer> parts = o.split() ? List.of(GuiLayer.GUI, GuiLayer.TOOLTIP) : List.of(o.layer());
        List<Path> files = o.split() ? List.of(splitPath(path, "gui"), splitPath(path, "tooltip")) : List.of(path);
        List<CompletableFuture<JsonObject>> written = new ArrayList<>();
        int[] restore = {-1};
        return MainThreadExecutor.submit(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (o.guiScale() != null) restore[0] = GuiScale.apply(mc, o.guiScale());
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            for (int i = 0; i < parts.size(); i++) {
                GuiLayer part = parts.get(i);
                // one canvas per part: the readback completes later, a shared canvas would be drawn over
                RenderTarget canvas = GuiCanvas.canvas(main.width, main.height, i);
                GuiCanvas.Drawn d = GuiCanvas.draw(part, canvas);
                if (part.needsScreen() && !d.screen()) {
                    throw new IllegalStateException("NO_SCREEN: no screen is open; open the menu first");
                }
                if (part == GuiLayer.TOOLTIP && !d.tooltip()) {
                    throw new IllegalStateException("NO_TOOLTIP: no tooltip is shown; hover an item first (gui action=hover slot=N)");
                }
                Path file = files.get(i);
                CompletableFuture<JsonObject> one = new CompletableFuture<>();
                int w = canvas.width, h = canvas.height;
                READER.read(canvas, pixels -> {
                    try {
                        one.complete(write(AlphaPixels.topDownStraight(pixels, w, h), w, h, o.trim(), file, part));
                    } catch (IOException | RuntimeException e) {
                        one.completeExceptionally(e);
                    }
                });
                written.add(one);
            }
            return true;
        }).thenCompose(v -> CompletableFuture.allOf(written.toArray(CompletableFuture[]::new)))
            .handle((v, error) -> {
                if (restore[0] > 0) {
                    MainThreadExecutor.submitVoid(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.options.guiScale().set(restore[0]);
                        mc.resizeGui();
                    });
                }
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    throw cause instanceof RuntimeException r ? r : new RuntimeException(cause.getMessage(), cause);
                }
                JsonObject out = new JsonObject();
                JsonArray images = new JsonArray();
                written.forEach(f -> images.add(f.join()));
                out.add("images", images);
                out.addProperty("path", images.get(0).getAsJsonObject().get("path").getAsString());
                out.addProperty("layer", o.layer().name().toLowerCase(Locale.ROOT).replace('_', '+'));
                return out;
            });
    }

    private static JsonObject write(byte[] px, int w, int h, boolean trim, Path file, GuiLayer part) throws IOException {
        int[] box = trim ? AlphaPixels.bounds(px, w, h, TRIM_PAD) : null;
        if (box == null) box = new int[] {0, 0, w, h};
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, box[2], box[3], false)) {
            for (int y = 0; y < box[3]; y++) {
                for (int x = 0; x < box[2]; x++) {
                    image.setPixel(x, y, AlphaPixels.argb(px, w, box[0] + x, box[1] + y));
                }
            }
            image.writeToFile(file);
        }
        JsonObject o = new JsonObject();
        o.addProperty("path", file.toAbsolutePath().toString());
        o.addProperty("part", part.name().toLowerCase(Locale.ROOT).replace('_', '+'));
        o.addProperty("width", box[2]);
        o.addProperty("height", box[3]);
        o.addProperty("x", box[0]);
        o.addProperty("y", box[1]);
        return o;
    }
}
