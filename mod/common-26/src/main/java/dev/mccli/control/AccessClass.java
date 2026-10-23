package dev.mccli.control;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;

/** What a command can do to the game, so the player can allow watching without allowing actions. */
public enum AccessClass {
    /** Reads state and captures pictures or video: nothing the server sees. */
    OBSERVE,
    /** Mouse, keys, screen clicks and client settings. */
    INPUT,
    /** Talks to the server or changes the world: chat, commands, joining servers, teleports. */
    WORLD;

    private static final Map<String, AccessClass> BY_COMMAND = Map.ofEntries(
        Map.entry("status", OBSERVE), Map.entry("players", OBSERVE), Map.entry("screenshot", OBSERVE),
        Map.entry("record", OBSERVE), Map.entry("logs", OBSERVE), Map.entry("perf", OBSERVE),
        Map.entry("item", OBSERVE), Map.entry("inventory", OBSERVE), Map.entry("block", OBSERVE),
        Map.entry("entity", OBSERVE), Map.entry("camera", OBSERVE), Map.entry("replay", OBSERVE),
        Map.entry("input", INPUT), Map.entry("interact", INPUT), Map.entry("window", INPUT),
        Map.entry("shader", INPUT), Map.entry("resourcepack", INPUT),
        Map.entry("chat", WORLD), Map.entry("execute", WORLD), Map.entry("server", WORLD),
        Map.entry("teleport", WORLD), Map.entry("time", WORLD), Map.entry("world", WORLD)
    );

    /** The class of one request. Unknown commands count as WORLD, the strictest class. */
    public static AccessClass of(String command, JsonObject params) {
        if ("gui".equals(command)) {
            String action = params != null && params.has("action") ? params.get("action").getAsString() : "read";
            return "read".equals(action) ? OBSERVE : INPUT;
        }
        return BY_COMMAND.getOrDefault(command, WORLD);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AccessClass parse(String id) {
        return valueOf(id.trim().toUpperCase(Locale.ROOT));
    }
}
