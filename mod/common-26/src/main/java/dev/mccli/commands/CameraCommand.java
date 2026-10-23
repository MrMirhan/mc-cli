package dev.mccli.commands;

import com.google.gson.JsonObject;
import dev.mccli.camera.CameraMath;
import dev.mccli.camera.CameraMotion;
import dev.mccli.camera.CameraMotionJson;
import dev.mccli.camera.CameraOverride;
import dev.mccli.camera.CameraOverride.Pose;
import dev.mccli.camera.CameraPath;
import dev.mccli.util.MainThreadExecutor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Player view direction, or a render-only camera that does not move the player.
 *
 * Without action (legacy): yaw, pitch turn the player.
 * Actions (render-only camera, the player stays where it is):
 * - set: x, y, z, yaw, pitch (any omitted value keeps the current one)
 * - look_at: target {x,y,z}
 * - frame_area: area {min:{x,y,z}, max:{x,y,z}}, yaw, pitch (default 30), distance_scale (default 1)
 * - frame_entity: entity (UUID or name), yaw, pitch, distance_scale
 * - orbit: center {x,y,z}, radius, durationMs, height, startAngle, degrees (default 360), ease ("none"|"inOut")
 * - path: keyframes [{t,x,y,z,yaw?,pitch?,fov?}], lookAt {x,y,z}, interpolation ("smooth"|"linear"), ease ("inOut"|"none")
 * - follow: entity (name, UUID or @self), distance (6), height (2), angle (180 = behind, 0 = in front),
 *   relative_to entity|world, smoothingMs (250), durationMs (0 = until reset), fov
 * - attach: rides with the entity's view (@self: turns as the player looks): forward (-4), up (1), right (0),
 *   yawOffset, pitchOffset, fov
 * - shot: shot push_in|pull_out|crane_up|crane_down|reveal|dolly_zoom, target {x,y,z}, durationMs, amount, fov, endFov
 * - fov: fov in degrees for the override camera (null: the game's own)
 * - freecam_on: freeze the camera where it is
 * - reset / freecam_off: back to the player's view
 * - status: the current camera pose
 */
public class CameraCommand implements Command {
    @Override
    public String getName() {
        return "camera";
    }

    @Override
    public CompletableFuture<JsonObject> execute(JsonObject params) {
        if (!params.has("action")) return turnPlayer(params);
        return MainThreadExecutor.submit(() -> handle(params));
    }

    private CompletableFuture<JsonObject> turnPlayer(JsonObject params) {
        if (!params.has("yaw") || !params.has("pitch")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Missing required parameters: yaw, pitch (or give an action)"));
        }
        float pitch = Math.max(-90, Math.min(90, params.get("pitch").getAsFloat()));
        float yaw = CameraPath.wrap(params.get("yaw").getAsFloat());
        return MainThreadExecutor.submit(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) throw new IllegalStateException("Player not in game");
            player.setYRot(yaw);
            player.setXRot(pitch);
            JsonObject result = new JsonObject();
            result.addProperty("yaw", player.getYRot());
            result.addProperty("pitch", player.getXRot());
            return result;
        });
    }

    /** Main thread: applies a camera action; without action the object is a pose for "set". */
    public static JsonObject handle(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw new IllegalStateException("Player not in game");
        String action = p.has("action") ? p.get("action").getAsString() : "set";
        Pose current = currentPose(mc);
        switch (action) {
            case "set" -> CameraOverride.INSTANCE.set(new Pose(
                    num(p, "x", current.x()), num(p, "y", current.y()), num(p, "z", current.z()),
                    (float) num(p, "yaw", current.yaw()), (float) num(p, "pitch", current.pitch())));
            case "look_at" -> {
                CameraMath.Vec target = CameraMotionJson.vec(obj(p, "target"), "target");
                CameraMath.Angles a = CameraMath.lookAt(new CameraMath.Vec(current.x(), current.y(), current.z()), target);
                CameraOverride.INSTANCE.set(new Pose(current.x(), current.y(), current.z(), a.yaw(), a.pitch()));
            }
            case "frame_area" -> {
                JsonObject area = obj(p, "area");
                if (area == null) throw new IllegalArgumentException("area {min,max} is required");
                frame(mc, p, current, CameraMotionJson.vec(obj(area, "min"), "area.min"), CameraMotionJson.vec(obj(area, "max"), "area.max"));
            }
            case "frame_entity" -> {
                if (!p.has("entity")) throw new IllegalArgumentException("entity is required");
                AABB box = findEntity(mc, p.get("entity").getAsString()).getBoundingBox().inflate(1.0);
                frame(mc, p, current, new CameraMath.Vec(box.minX, box.minY, box.minZ), new CameraMath.Vec(box.maxX, box.maxY, box.maxZ));
            }
            case "path", "orbit", "follow", "attach", "shot" -> CameraOverride.INSTANCE.play(CameraMotionJson.parse(p, current), System.nanoTime());
            case "fov" -> CameraOverride.INSTANCE.setFov(p.has("fov") && !p.get("fov").isJsonNull() ? p.get("fov").getAsDouble() : null);
            case "freecam_on" -> CameraOverride.INSTANCE.set(current);
            case "freecam_off", "reset" -> CameraOverride.INSTANCE.clear();
            case "status" -> { }
            default -> throw new IllegalArgumentException("unknown action: " + action);
        }
        return describe(mc);
    }

    private static void frame(Minecraft mc, JsonObject p, Pose current, CameraMath.Vec min, CameraMath.Vec max) {
        float yaw = (float) num(p, "yaw", current.yaw());
        float pitch = (float) num(p, "pitch", 30);
        CameraMath.Vec pos = CameraMath.frame(min, max, yaw, pitch, mc.options.fov().get(), num(p, "distance_scale", 1.0));
        CameraOverride.INSTANCE.set(new Pose(pos.x(), pos.y(), pos.z(), yaw, pitch));
    }

    private static Entity findEntity(Minecraft mc, String key) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().toString().equalsIgnoreCase(key) || e.getName().getString().equalsIgnoreCase(key)) return e;
        }
        throw new IllegalArgumentException("No loaded entity matches \"" + key + "\" (UUID or exact name, within render distance)");
    }

    /** The pose the next frame renders from: the override, or the vanilla camera. */
    public static Pose currentPose(Minecraft mc) {
        Pose o = CameraOverride.INSTANCE.get();
        if (o != null) return o;
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 pos = cam.position();
        return new Pose(pos.x, pos.y, pos.z, cam.yRot(), cam.xRot());
    }

    private static JsonObject describe(Minecraft mc) {
        Pose p = currentPose(mc);
        JsonObject o = new JsonObject();
        o.addProperty("active", CameraOverride.INSTANCE.get() != null);
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        o.addProperty("yaw", p.yaw());
        o.addProperty("pitch", p.pitch());
        CameraMotion m = CameraOverride.INSTANCE.motion();
        if (m != null) {
            JsonObject motion = new JsonObject();
            motion.addProperty("kind", m.kind());
            motion.addProperty("durationMs", m.durationMs());
            o.add("motion", motion);
        }
        double limit = mc.options.renderDistance().get() * 16.0;
        double dist = mc.player.getEyePosition().distanceTo(new Vec3(p.x(), p.y(), p.z()));
        if (dist > limit) {
            o.addProperty("warning", String.format("Camera is %.0f blocks from the player; chunks beyond render distance (%.0f) are not drawn.", dist, limit));
        }
        return o;
    }

    private static double num(JsonObject p, String key, double fallback) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsDouble() : fallback;
    }

    private static JsonObject obj(JsonObject p, String key) {
        return p.has(key) && p.get(key).isJsonObject() ? p.getAsJsonObject(key) : null;
    }
}
