package dev.mccli.pilot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where the pilot may act: a singleplayer world, or a server whose exact host:port the player listed in
 * config/mccli.json as "pilotServers". Automated movement and clicks on any other server count as cheating.
 */
public final class PilotGuard {
    private PilotGuard() {}

    public static Set<String> listed(Path configDir) {
        Set<String> out = new HashSet<>();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(configDir.resolve("mccli.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("pilotServers") && json.get("pilotServers").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("pilotServers")) out.add(normalize(e.getAsString()));
            }
        } catch (IOException | RuntimeException ignored) {
            // no file or a broken one: nothing is listed
        }
        return out;
    }

    /** null when allowed, else why not. */
    public static String refusal(boolean singleplayer, String serverAddress, Set<String> listed) {
        if (singleplayer) return null;
        if (serverAddress == null) return "not in a world";
        String address = normalize(serverAddress);
        if (listed.contains(address)) return null;
        return "the pilot only acts in singleplayer or on servers listed in config/mccli.json pilotServers (exact host:port); "
            + address + " is not listed. Automated play on other servers is cheating.";
    }

    static String normalize(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        return a.contains(":") ? a : a + ":25565";
    }
}
