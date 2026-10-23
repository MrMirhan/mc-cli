package dev.mccli.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/** The MCP tools this game offers: one per command, with a short description and the main parameters. */
final class McpTools {
    record Tool(String name, String description, JsonObject schema) {}

    static final Map<String, Tool> ALL = new LinkedHashMap<>();

    private static void add(String name, String description, String properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", JsonParser.parseString(properties).getAsJsonObject());
        if (required.length > 0) {
            JsonArray r = new JsonArray();
            for (String s : required) r.add(s);
            schema.add("required", r);
        }
        schema.addProperty("additionalProperties", true);
        ALL.put(name, new Tool(name, description, schema));
    }

    private static final String ACTION = "\"action\":{\"type\":\"string\"}";

    static {
        add("status", "Game state: player position, world, server, screen, shader.", "{}");
        add("control", "What the player allows this tool to do: mode, classes, paused, consent.", "{}");
        add("screenshot", "Save a screenshot (path: absolute .png). clean hides the HUD. camera renders from another pose "
            + "without moving the player. layer gui|tooltip|gui+tooltip saves the open menu and/or its tooltip on transparency "
            + "(split, gui_scale 1-8, trim). The image is returned too.",
            "{\"path\":{\"type\":\"string\"},\"clean\":{\"type\":\"boolean\"},\"camera\":{\"type\":\"object\"},"
            + "\"layer\":{\"type\":\"string\",\"enum\":[\"world\",\"gui\",\"tooltip\",\"gui+tooltip\"]},\"split\":{\"type\":\"boolean\"},"
            + "\"gui_scale\":{\"type\":\"integer\"},\"trim\":{\"type\":\"boolean\"}}", "path");
        add("record", "Record video with game sound. action start|stop|status. path .mp4 (.mov/.webm with a transparent layer). "
            + "preset max|high|balanced|light, priority smooth (60 fps)|light (30 fps), camera motion (orbit, path, follow, attach, shot), "
            + "duration_ms, wait. An events log is written next to the video.",
            "{" + ACTION + ",\"path\":{\"type\":\"string\"},\"preset\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\"},"
            + "\"camera\":{\"type\":\"object\"},\"duration_ms\":{\"type\":\"number\"},\"wait\":{\"type\":\"boolean\"},"
            + "\"layer\":{\"type\":\"string\"},\"hud\":{\"type\":\"boolean\"}}", "action");
        add("camera", "Render-only camera, the player does not move: set, look_at, frame_area, frame_entity, orbit, path, "
            + "follow (entity, distance, angle, smoothingMs), attach (rides with the player's view), shot (push_in, pull_out, "
            + "crane_up, crane_down, reveal, dolly_zoom), fov, reset, status.", "{" + ACTION + "}", "action");
        add("gui", "The open screen: read (widgets, slots with lore and tooltip), hover, click, right_click, shift_click, "
            + "button {text}, close.", "{" + ACTION + ",\"slot\":{\"type\":\"integer\"},\"text\":{\"type\":\"string\"}}");
        add("input", "Mouse and keys as the window gets them: mouse_move, mouse_click, key, type, slots, slot_click.", "{" + ACTION + "}", "action");
        add("events", "What happened, in order: read {since, types, limit} returns events and next; packets {mode off|summary|all}.",
            "{" + ACTION + ",\"since\":{\"type\":\"integer\"},\"types\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"limit\":{\"type\":\"integer\"}}");
        add("chat", "Chat: send {message}, history {limit, type}, clear.", "{" + ACTION + "}", "action");
        add("execute", "Run a command as the player (without the slash).", "{\"command\":{\"type\":\"string\"}}", "command");
        add("server", "connect {address, port}, disconnect, status, connection_error.", "{" + ACTION + "}", "action");
        add("players", "The tab list and nearby players with positions.", "{}");
        add("inventory", "The player's inventory.", "{}");
        add("item", "The held item or a slot, with components.", "{}");
        add("block", "The targeted block or one at x, y, z.", "{}");
        add("entity", "The targeted entity.", "{}");
        add("interact", "use, use_on_block, use_on_entity, attack, drop, swap, select.", "{" + ACTION + "}", "action");
        add("teleport", "Teleport the player (sends a command).", "{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}}");
        add("time", "World time: get, set {value}.", "{" + ACTION + "}");
        add("shader", "Iris shaders: list, get, set {name}, reload, errors, disable.", "{" + ACTION + "}", "action");
        add("resourcepack", "Resource packs: list, enabled, enable, disable, reload.", "{" + ACTION + "}", "action");
        add("window", "Window: hide, show, focus, close_screen, status.", "{" + ACTION + "}", "action");
        add("world", "Singleplayer worlds: list, load, create, delete.", "{" + ACTION + "}", "action");
        add("replay", "Render a ReplayMod replay through its own API.", "{" + ACTION + "}", "action");
        add("perf", "FPS, memory and chunk metrics.", "{}");
        add("logs", "Recent game log lines.", "{}");
    }

    private McpTools() {}

    static JsonArray list() {
        JsonArray tools = new JsonArray();
        for (Tool t : ALL.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.schema());
            tools.add(o);
        }
        return tools;
    }
}
