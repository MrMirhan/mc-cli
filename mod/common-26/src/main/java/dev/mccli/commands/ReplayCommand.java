package dev.mccli.commands;

import com.google.gson.JsonObject;
import dev.mccli.McCliMod;
import dev.mccli.util.HeadlessMode;
import dev.mccli.util.MainThreadExecutor;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Render the jobs queued inside a ReplayMod replay (renderQueue.json in the .mcpr) without the Replay Viewer:
 * the same call as its "Bulk Render" button, so no list click, Ctrl-click or render button is needed.
 * ReplayMod is optional and reached by reflection; without it the command says so.
 *
 * Params:
 * - action: render | status (default status)
 * render:
 * - path: absolute .mcpr path (required); its queue must not be empty
 *
 * render returns {started, jobs, path}; status returns {state: idle|rendering|done|failed, path, jobs, error}.
 * Videos land where each job's settings say (ReplayMod's output file). A client started headless is refused:
 * there ReplayMod's replay sender stops after the first frame and the game crashes; start it with a window.
 */
public class ReplayCommand implements Command {
    private static volatile String state = "idle";
    private static volatile String current;
    private static volatile int jobs;
    private static volatile String error;

    @Override
    public String getName() {
        return "replay";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "status";
        return switch (action) {
            case "status" -> CompletableFuture.completedFuture(status());
            case "render" -> render(params);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("action must be render or status"));
        };
    }

    private static JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        if (current != null) o.addProperty("path", current);
        o.addProperty("jobs", jobs);
        if (error != null) o.addProperty("error", error);
        return o;
    }

    private CompletableFuture<JsonObject> render(JsonObject params) {
        if (!params.has("path")) return CompletableFuture.failedFuture(new IllegalArgumentException("Missing required parameter: path"));
        File file = new File(params.get("path").getAsString());
        if (!file.isAbsolute() || !file.isFile() || !file.getName().endsWith(".mcpr")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("path must be an existing absolute .mcpr file"));
        }
        if ("rendering".equals(state)) return CompletableFuture.failedFuture(new IllegalStateException("a replay is already rendering: " + current));
        if (HeadlessMode.isRequested()) {
            return CompletableFuture.failedFuture(new IllegalStateException("replay rendering needs a client started with a window (cam.py start --window)"));
        }
        return MainThreadExecutor.submit(() -> {
            try {
                Class<?> core = Class.forName("com.replaymod.core.ReplayMod");
                Class<?> replayModule = Class.forName("com.replaymod.replay.ReplayModReplay");
                Class<?> renderJob = Class.forName("com.replaymod.render.utils.RenderJob");
                Class<?> replayFile = Class.forName("com.replaymod.replaystudio.replay.ReplayFile");
                Class<?> queue = Class.forName("com.replaymod.render.gui.GuiRenderQueue");
                Class<?> screen = Class.forName("com.replaymod.lib.de.johni0702.minecraft.gui.container.AbstractGuiScreen");
                Class<?> pair = Class.forName("com.replaymod.lib.org.apache.commons.lang3.tuple.Pair");

                Object mod = replayModule.getField("instance").get(null);
                Object files = core.getField("files").get(core.getField("instance").get(null));
                List<?> queued;
                Object opened = files.getClass().getMethod("open", java.nio.file.Path.class).invoke(files, file.toPath());
                try {
                    queued = (List<?>) renderJob.getMethod("readQueue", replayFile).invoke(null, opened);
                } finally {
                    ((Closeable) opened).close();
                }
                if (queued.isEmpty()) throw new IllegalStateException("the replay has no render queue; run tools/replay.py queue first");

                Object viewer = Class.forName("com.replaymod.replay.gui.screen.GuiReplayViewer").getConstructor(replayModule).newInstance(mod);
                screen.getMethod("display").invoke(viewer);
                Iterator<?> work = List.of(pair.getMethod("of", Object.class, Object.class).invoke(null, file, queued)).iterator();
                Method run = queue.getMethod("processMultipleReplays", screen, replayModule, Iterator.class, Runnable.class);
                state = "rendering";
                current = file.getAbsolutePath();
                jobs = queued.size();
                error = null;
                run.invoke(null, viewer, mod, work, (Runnable) () -> {
                    state = "done";
                    McCliMod.LOGGER.info("Replay render finished: {}", current);
                });
                JsonObject o = new JsonObject();
                o.addProperty("started", true);
                o.addProperty("jobs", jobs);
                o.addProperty("path", current);
                return o;
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("ReplayMod is not installed in this client");
            } catch (ReflectiveOperationException | java.io.IOException | RuntimeException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                if ("rendering".equals(state)) {
                    state = "failed";
                    error = String.valueOf(cause.getMessage());
                }
                throw cause instanceof RuntimeException r ? r : new RuntimeException(cause.getMessage(), cause);
            }
        });
    }
}
