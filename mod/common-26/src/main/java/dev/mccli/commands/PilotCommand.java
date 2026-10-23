package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mccli.pilot.Pilot;
import dev.mccli.util.MainThreadExecutor;

import java.util.concurrent.CompletableFuture;

/**
 * Plays steps with the player's own keys and crosshair, tick by tick, like a person; returns at once. Only in
 * singleplayer or on servers listed in config/mccli.json "pilotServers". F8 pauses it; an open screen holds it.
 *
 * - run: steps [{do, ...}], profile rookie|mid|tryhard (how fast the head turns and how well it aims)
 *     walk_to {x, z}            walk there (jumps over one-block steps), fails after 60 s
 *     look_at {x, y, z}         turn the head to a point
 *     place {x, y, z, item?}    put the held (or named hotbar) block at that position against a neighbour
 *     break {x, y, z}           hold attack on the block until it breaks
 *     select {slot | item}      hotbar slot 0-8, or the first hotbar slot with that item id
 *     jump, wait {ms}
 *     follow {target, distance} keep near a player or mob
 *     fight {target, style 1.8|1.9, seconds} close-range fighting
 * - status: running, current step, queued, done, failed, lastError
 * - stop
 * Every finished step adds pilot_done or pilot_failed to the event log; a failed step clears the rest.
 */
public class PilotCommand implements Command {
    @Override
    public String getName() {
        return "pilot";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "status";
        return switch (action) {
            case "run" -> {
                if (!params.has("steps") || !params.get("steps").isJsonArray()) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("run needs steps [{do, ...}]"));
                }
                JsonArray steps = params.getAsJsonArray("steps");
                String profile = params.has("profile") ? params.get("profile").getAsString() : "mid";
                yield MainThreadExecutor.submit(() -> Pilot.run(steps, profile));
            }
            case "stop" -> MainThreadExecutor.submit(() -> {
                Pilot.stop("requested");
                return Pilot.status();
            });
            case "status" -> MainThreadExecutor.submit(Pilot::status);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("action must be run, status or stop"));
        };
    }
}
