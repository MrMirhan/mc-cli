package dev.mccli.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mccli.events.EventLog;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The event log: chat, system, title, subtitle, actionbar, screen_open, screen_close, agent (this tool's commands),
 * player (position while a video records), packets (counts per second), packet (one per packet), record_start,
 * record_stop.
 *
 * - read: since (cursor, default 0), types (list), limit (default 200, at most 1000); returns events, next, latest
 * - packets: mode off | summary | all (off by default; all is a lot)
 */
public class EventsCommand implements Command {
    @Override
    public String getName() {
        return "events";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "read";
        try {
            return CompletableFuture.completedFuture(switch (action) {
                case "read" -> {
                    long since = params.has("since") ? params.get("since").getAsLong() : 0;
                    int limit = Math.min(1000, params.has("limit") ? params.get("limit").getAsInt() : 200);
                    Set<String> types = new HashSet<>();
                    if (params.has("types") && params.get("types").isJsonArray()) {
                        for (JsonElement t : params.getAsJsonArray("types")) types.add(t.getAsString());
                    }
                    yield EventLog.read(since, types, limit);
                }
                case "packets" -> {
                    String mode = params.has("mode") ? params.get("mode").getAsString() : "summary";
                    EventLog.packets(EventLog.Packets.valueOf(mode.toUpperCase(Locale.ROOT)));
                    JsonObject o = new JsonObject();
                    o.addProperty("packets", mode);
                    yield o;
                }
                default -> throw new IllegalArgumentException("action must be read or packets");
            });
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
