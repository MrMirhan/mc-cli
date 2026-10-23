package dev.mccli.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mccli.util.MainThreadExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The blocks around the player in one compact answer, so a tool can read its surroundings without a screenshot.
 *
 * Params: radius (horizontal, default 6, at most 16), below (default 2), above (default 4).
 * Response: origin {x,y,z} (the minimum corner), size {x,y,z}, palette {char: block id} (". " is air), layers: one
 * entry per y from bottom to top, each a list of rows (z from north to south), each row a string of x from west to
 * east. Also the player's block position, facing, and the block under the crosshair.
 */
public class ScanCommand implements Command {
    private static final String CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*+=?!";
    private static final int MAX_BLOCKS = 40_000;

    @Override
    public String getName() {
        return "scan";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        int radius = params.has("radius") ? params.get("radius").getAsInt() : 6;
        int below = params.has("below") ? params.get("below").getAsInt() : 2;
        int above = params.has("above") ? params.get("above").getAsInt() : 4;
        if (radius < 1 || radius > 16 || below < 0 || above < 0 || below + above > 32) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("radius 1-16, below and above 0-32 together"));
        }
        long volume = (2L * radius + 1) * (2L * radius + 1) * (below + above + 1);
        if (volume > MAX_BLOCKS) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("too many blocks (" + volume + "); lower radius or height"));
        }
        return MainThreadExecutor.submit(() -> scan(radius, below, above));
    }

    private static JsonObject scan(int radius, int below, int above) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) throw new IllegalStateException("Player not in game");
        BlockPos feet = player.blockPosition();
        BlockPos min = feet.offset(-radius, -below, -radius);
        int sx = 2 * radius + 1, sy = below + above + 1;
        Map<String, Character> chars = new LinkedHashMap<>();
        JsonObject palette = new JsonObject();
        JsonArray layers = new JsonArray();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            JsonArray rows = new JsonArray();
            for (int z = 0; z < sx; z++) {
                StringBuilder row = new StringBuilder(sx);
                for (int x = 0; x < sx; x++) {
                    p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    BlockState state = mc.level.getBlockState(p);
                    if (state.isAir()) {
                        row.append('.');
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    Character c = chars.get(id);
                    if (c == null) {
                        c = chars.size() < CHARS.length() ? CHARS.charAt(chars.size()) : '~';
                        if (c != '~') {
                            chars.put(id, c);
                            palette.addProperty(String.valueOf(c), id);
                        }
                    }
                    row.append(c);
                }
                rows.add(row.toString());
            }
            layers.add(rows);
        }
        if (chars.size() >= CHARS.length()) palette.addProperty("~", "other blocks (more kinds than symbols)");
        palette.addProperty(".", "air");
        JsonObject out = new JsonObject();
        out.add("origin", xyz(min));
        JsonObject size = new JsonObject();
        size.addProperty("x", sx);
        size.addProperty("y", sy);
        size.addProperty("z", sx);
        out.add("size", size);
        out.add("player", xyz(feet));
        out.addProperty("facing", player.getDirection().getName());
        out.addProperty("yaw", player.getYRot());
        out.addProperty("pitch", player.getXRot());
        if (mc.hitResult instanceof BlockHitResult hit && !mc.level.getBlockState(hit.getBlockPos()).isAir()) {
            JsonObject t = xyz(hit.getBlockPos());
            t.addProperty("face", hit.getDirection().getName());
            t.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            out.add("crosshair", t);
        }
        out.add("palette", palette);
        out.add("layers", layers);
        return out;
    }

    private static JsonObject xyz(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }
}
