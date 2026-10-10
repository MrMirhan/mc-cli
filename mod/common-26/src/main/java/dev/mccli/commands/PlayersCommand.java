package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mccli.util.MainThreadExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/**
 * List players: the tab list (every player the server reports) and the players loaded nearby with positions.
 *
 * Response:
 * - tab: [{name, game_mode, latency}]
 * - nearby: [{name, uuid, x, y, z, yaw, pitch, health, distance}] (players in the client's loaded world, minus self)
 */
public class PlayersCommand implements Command {
    @Override
    public String getName() {
        return "players";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        return MainThreadExecutor.submit(() -> {
            Minecraft client = Minecraft.getInstance();
            JsonObject result = new JsonObject();
            JsonArray tab = new JsonArray();
            ClientPacketListener connection = client.getConnection();
            if (connection != null) {
                for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", info.getProfile().name());
                    p.addProperty("game_mode", info.getGameMode() == null ? "" : info.getGameMode().getName());
                    p.addProperty("latency", info.getLatency());
                    tab.add(p);
                }
            }
            result.add("tab", tab);

            JsonArray nearby = new JsonArray();
            if (client.level != null && client.player != null) {
                for (Player other : client.level.players()) {
                    if (other == client.player) {
                        continue;
                    }
                    JsonObject p = new JsonObject();
                    p.addProperty("name", other.getGameProfile().name());
                    p.addProperty("uuid", other.getUUID().toString());
                    p.addProperty("x", other.getX());
                    p.addProperty("y", other.getY());
                    p.addProperty("z", other.getZ());
                    p.addProperty("yaw", other.getYRot());
                    p.addProperty("pitch", other.getXRot());
                    p.addProperty("health", other.getHealth());
                    p.addProperty("distance", other.distanceTo(client.player));
                    nearby.add(p);
                }
            }
            result.add("nearby", nearby);
            return result;
        });
    }
}
