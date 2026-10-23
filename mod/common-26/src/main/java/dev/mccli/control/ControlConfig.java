package dev.mccli.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * config/mccli.json: {"remoteControl": "off|ask|on", "allow": ["observe", "input", "world"]}.
 *
 * Without a file a hidden client started by a tool is {@code on} with every class (nobody is there to ask), and a
 * player's own windowed game is {@code ask} with observe and input; the file is written so the player can change it.
 */
public final class ControlConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String FILE = "mccli.json";

    private ControlConfig() {}

    public static ControlPolicy load(Path configDir, boolean headless) {
        Path file = configDir.resolve(FILE);
        String env = System.getenv("MCCLI_CONTROL");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return fromJson(json, env);
            } catch (IOException | RuntimeException e) {
                // A broken file must not open control: refuse until the player fixes it.
                return new ControlPolicy(ControlPolicy.Mode.OFF, EnumSet.noneOf(AccessClass.class));
            }
        }
        ControlPolicy policy = headless
            ? new ControlPolicy(ControlPolicy.Mode.ON, EnumSet.allOf(AccessClass.class))
            : new ControlPolicy(ControlPolicy.Mode.ASK, EnumSet.of(AccessClass.OBSERVE, AccessClass.INPUT));
        if (env != null) {
            policy = new ControlPolicy(parseMode(env), policy.allowed());
        }
        write(file, policy);
        return policy;
    }

    static ControlPolicy fromJson(JsonObject json, String envMode) {
        ControlPolicy.Mode mode = parseMode(envMode != null ? envMode
            : json.has("remoteControl") ? json.get("remoteControl").getAsString() : "ask");
        Set<AccessClass> allow = EnumSet.noneOf(AccessClass.class);
        if (json.has("allow") && json.get("allow").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("allow")) {
                allow.add(AccessClass.parse(e.getAsString()));
            }
        } else {
            allow.add(AccessClass.OBSERVE);
            allow.add(AccessClass.INPUT);
        }
        return new ControlPolicy(mode, allow);
    }

    static ControlPolicy.Mode parseMode(String value) {
        return ControlPolicy.Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static void write(Path file, ControlPolicy policy) {
        JsonObject json = new JsonObject();
        json.addProperty("remoteControl", policy.mode().name().toLowerCase(Locale.ROOT));
        JsonArray allow = new JsonArray();
        policy.allowed().forEach(c -> allow.add(c.id()));
        json.add("allow", allow);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Read-only config folder: the defaults above still apply for this session.
        }
    }
}
