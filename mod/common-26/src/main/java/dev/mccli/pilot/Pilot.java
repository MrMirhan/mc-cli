package dev.mccli.pilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mccli.control.ControlRuntime;
import dev.mccli.events.EventLog;
import dev.mccli.mixin.KeyMappingAccessor;
import dev.mccli.util.ClientCompat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Hands for the game: walks, looks, places and breaks blocks, picks hotbar items and fights through the same key
 * mappings and crosshair a player uses, one decision per tick, with a person's head movement. A tool sends a list
 * of steps and the pilot plays them while the tool thinks about the next thing. Render thread only.
 */
public final class Pilot {
    private static final double REACH = 4.4;
    private static final long PLACE_TIMEOUT = 4000, BREAK_TIMEOUT = 15000;

    private record Step(int index, JsonObject json) {}

    private static final Deque<Step> queue = new ArrayDeque<>();
    private static Step current;
    private static PilotBrain brain;
    private static HumanModel.Gaze gaze;
    private static long stepStart, lastTick;
    private static boolean acted;
    private static String profile = "mid";
    private static int done, failed;
    private static String lastError;

    private Pilot() {}

    /** Replaces whatever runs with these steps. */
    public static synchronized JsonObject run(JsonArray steps, String profileName) {
        Minecraft mc = Minecraft.getInstance();
        String refused = refusal(mc);
        if (refused != null) throw new IllegalStateException("PILOT_REFUSED: " + refused);
        HumanModel.profile(profileName);
        stop("replaced");
        profile = profileName;
        int i = 0;
        for (JsonElement e : steps) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("do")) throw new IllegalArgumentException("step " + i + " needs \"do\"");
            queue.add(new Step(i++, e.getAsJsonObject()));
        }
        done = failed = 0;
        lastError = null;
        return status();
    }

    public static synchronized void stop(String why) {
        if (current != null || !queue.isEmpty()) {
            JsonObject e = new JsonObject();
            e.addProperty("reason", why);
            EventLog.add("pilot_stop", e);
        }
        queue.clear();
        current = null;
        brain = null;
        gaze = null;
        release(Minecraft.getInstance());
    }

    public static synchronized JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("running", current != null || !queue.isEmpty());
        if (current != null) {
            o.addProperty("step", current.index());
            o.add("current", current.json());
        }
        o.addProperty("queued", queue.size());
        o.addProperty("done", done);
        o.addProperty("failed", failed);
        if (lastError != null) o.addProperty("lastError", lastError);
        if (brain != null) o.addProperty("hits", brain.hits());
        return o;
    }

    static String refusal(Minecraft mc) {
        var server = mc.getCurrentServer();
        return PilotGuard.refusal(mc.hasSingleplayerServer(), server == null ? null : server.ip,
            PilotGuard.listed(ControlRuntime.configDir()));
    }

    /** Every client tick. */
    public static synchronized void tick(Minecraft mc) {
        if (current == null && queue.isEmpty()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop("left the world");
            return;
        }
        String refused = refusal(mc);
        if (refused != null) {
            lastError = refused;
            stop("server not allowed");
            return;
        }
        if (ControlRuntime.policy().paused() || ClientCompat.screen(mc) != null) {
            release(mc);
            return;
        }
        long now = System.currentTimeMillis();
        if (current == null) {
            current = queue.poll();
            brain = null;
            gaze = new HumanModel.Gaze(HumanModel.Rng.of("pilot" + now), HumanModel.profile(profile), player.getYRot(), player.getXRot());
            stepStart = now;
            lastTick = now;
            acted = false;
        }
        double dt = Math.max(0.001, (now - lastTick) / 1000.0);
        lastTick = now;
        try {
            if (step(mc, player, current.json(), now, dt)) finish(mc, null);
        } catch (RuntimeException e) {
            finish(mc, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void finish(Minecraft mc, String error) {
        JsonObject e = new JsonObject();
        e.addProperty("step", current.index());
        e.addProperty("do", current.json().get("do").getAsString());
        if (error != null) {
            e.addProperty("error", error);
            lastError = "step " + current.index() + ": " + error;
            failed++;
        } else {
            done++;
        }
        if (brain != null) e.addProperty("hits", brain.hits());
        EventLog.add(error == null ? "pilot_done" : "pilot_failed", e);
        release(mc);
        current = null;
        brain = null;
        if (error != null) {
            // later steps usually depend on this one: stop instead of building on a mistake
            queue.clear();
        }
    }

    /** One tick of a step; true when it is complete. */
    private static boolean step(Minecraft mc, LocalPlayer player, JsonObject s, long now, double dt) {
        String what = s.get("do").getAsString().toLowerCase(Locale.ROOT);
        return switch (what) {
            case "wait" -> now - stepStart >= num(s, "ms", 500);
            case "jump" -> {
                mc.options.keyJump.setDown(now - stepStart < 120);
                yield now - stepStart >= 400;
            }
            case "select" -> select(player, s);
            case "look_at" -> look(player, center(s), dt) < 1.5;
            case "walk_to", "follow", "fight" -> drive(mc, player, s, what, now);
            case "place" -> place(mc, player, s, now, dt);
            case "break" -> breakBlock(mc, player, s, now, dt);
            default -> throw new IllegalArgumentException("unknown step " + what
                + "; use walk_to, look_at, place, break, select, jump, wait, follow or fight");
        };
    }

    private static boolean drive(Minecraft mc, LocalPlayer player, JsonObject s, String what, long now) {
        if (brain == null) {
            long seconds = (long) num(s, "seconds", 0);
            PilotBrain.Mode mode = switch (what) {
                case "fight" -> PilotBrain.Mode.FIGHT;
                case "follow" -> PilotBrain.Mode.FOLLOW;
                default -> PilotBrain.Mode.WALK;
            };
            brain = new PilotBrain(mode, s.has("profile") ? s.get("profile").getAsString() : profile,
                s.has("style") ? s.get("style").getAsString() : "1.8", num(s, "distance", 3),
                seconds > 0 ? now + seconds * 1000 : (mode == PilotBrain.Mode.WALK ? now + 60_000 : 0),
                s.has("seed") ? s.get("seed").getAsString() : "pilot");
        }
        Double tx, ty, tz;
        if ("walk_to".equals(what)) {
            tx = num(s, "x", player.getX());
            ty = player.getY();
            tz = num(s, "z", player.getZ());
        } else {
            Entity e = entity(mc, s.has("target") ? s.get("target").getAsString() : "");
            tx = e == null ? null : e.getX();
            ty = e == null ? null : e.getY();
            tz = e == null ? null : e.getZ();
        }
        PilotBrain.Act a = brain.tick(new PilotBrain.Sense(now, player.getX(), player.getY(), player.getEyeY(), player.getZ(),
            player.getYRot(), player.getXRot(), player.getHealth(), player.onGround(), player.horizontalCollision, tx, ty, tz));
        player.setYRot((float) a.yaw());
        player.setXRot((float) a.pitch());
        Options o = mc.options;
        o.keyUp.setDown(a.forward());
        o.keyDown.setDown(a.back());
        o.keyLeft.setDown(a.left());
        o.keyRight.setDown(a.right());
        o.keySprint.setDown(a.sprint());
        o.keyJump.setDown(a.jump());
        if (a.attack() || a.swingOnly()) click(o.keyAttack);
        if (a.done() && "walk_to".equals(what) && Math.hypot(tx - player.getX(), tz - player.getZ()) > 1.5) {
            throw new IllegalStateException("could not reach " + Math.round(tx) + ", " + Math.round(tz) + " within 60 s");
        }
        return a.done();
    }

    private static boolean select(LocalPlayer player, JsonObject s) {
        var inv = player.getInventory();
        if (s.has("slot")) {
            int slot = s.get("slot").getAsInt();
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be a hotbar slot 0-8");
            inv.setSelectedSlot(slot);
            return true;
        }
        String id = s.has("item") ? s.get("item").getAsString() : "";
        for (int i = 0; i < 9; i++) {
            String key = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).toString();
            if (!inv.getItem(i).isEmpty() && (key.equals(id) || key.equals("minecraft:" + id))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        throw new IllegalStateException("no " + id + " in the hotbar; put it there first");
    }

    private static boolean place(Minecraft mc, LocalPlayer player, JsonObject s, long now, double dt) {
        BlockPos pos = pos(s);
        if (!mc.level.getBlockState(pos).canBeReplaced()) {
            if (acted) return true;
            throw new IllegalStateException(pos.toShortString() + " is not empty");
        }
        if (s.has("item") && !acted && now == stepStart) select(player, s);
        if (now - stepStart > PLACE_TIMEOUT) throw new IllegalStateException("could not place at " + pos.toShortString());
        if (acted) return false;
        Direction against = support(mc, pos);
        if (against == null) throw new IllegalStateException("nothing next to " + pos.toShortString() + " to place against");
        BlockPos support = pos.relative(against);
        Vec3 face = Vec3.atCenterOf(pos).add(against.getStepX() * 0.5, against.getStepY() * 0.5, against.getStepZ() * 0.5);
        if (player.getEyePosition().distanceTo(face) > REACH) {
            throw new IllegalStateException(pos.toShortString() + " is out of reach; walk_to closer first");
        }
        double off = look(player, face, dt);
        if (off < 4 && mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(support)
            && hit.getDirection() == against.getOpposite()) {
            click(mc.options.keyUse);
            acted = true;
        }
        return false;
    }

    private static boolean breakBlock(Minecraft mc, LocalPlayer player, JsonObject s, long now, double dt) {
        BlockPos pos = pos(s);
        if (mc.level.getBlockState(pos).isAir()) {
            mc.options.keyAttack.setDown(false);
            return true;
        }
        if (now - stepStart > BREAK_TIMEOUT) throw new IllegalStateException("could not break " + pos.toShortString());
        Vec3 c = Vec3.atCenterOf(pos);
        if (player.getEyePosition().distanceTo(c) > REACH + 0.5) {
            throw new IllegalStateException(pos.toShortString() + " is out of reach; walk_to closer first");
        }
        double off = look(player, c, dt);
        boolean onIt = mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(pos);
        mc.options.keyAttack.setDown(off < 6 && onIt);
        return false;
    }

    /** Turns the head toward a point like a person; returns how far off it still is, in degrees. */
    private static double look(LocalPlayer player, Vec3 at, double dt) {
        Vec3 eye = player.getEyePosition();
        double[] want = HumanModel.angles(eye.x, eye.y, eye.z, at.x, at.y, at.z);
        double[] now = gaze.step(dt, want[0], want[1]);
        player.setYRot((float) now[0]);
        player.setXRot((float) now[1]);
        return Math.hypot(HumanModel.wrap(want[0] - now[0]), want[1] - now[1]);
    }

    /** The side of {@code pos} that has a block to place against: below first, then the sides, then above. */
    private static Direction support(Minecraft mc, BlockPos pos) {
        for (Direction d : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            if (!mc.level.getBlockState(pos.relative(d)).canBeReplaced()) return d;
        }
        return null;
    }

    private static Entity entity(Minecraft mc, String key) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e != mc.player && (e.getUUID().toString().equalsIgnoreCase(key) || e.getName().getString().equalsIgnoreCase(key))) return e;
        }
        return null;
    }

    private static void click(KeyMapping key) {
        KeyMapping.click(((KeyMappingAccessor) key).mccli$key());
    }

    private static void release(Minecraft mc) {
        Options o = mc.options;
        if (o == null) return;
        for (KeyMapping k : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyAttack, o.keyUse}) {
            k.setDown(false);
        }
    }

    private static BlockPos pos(JsonObject s) {
        for (String k : new String[] {"x", "y", "z"}) {
            if (!s.has(k)) throw new IllegalArgumentException("needs x, y and z");
        }
        return new BlockPos(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
    }

    private static Vec3 center(JsonObject s) {
        return new Vec3(num(s, "x", 0), num(s, "y", 0), num(s, "z", 0));
    }

    private static double num(JsonObject s, String key, double fallback) {
        return s.has(key) ? s.get(key).getAsDouble() : fallback;
    }
}
