package dev.mccli.camera;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mccli.camera.CameraMath.Vec;

import java.util.ArrayList;
import java.util.List;

/** Reads a camera path / orbit from command parameters (camera command, record start's camera). */
public final class CameraMotionJson {
    private CameraMotionJson() {}

    /** @param current the camera pose now; an orbit without startAngle starts from where it is */
    public static CameraMotion parse(JsonObject p, CameraOverride.Pose current) {
        String action = str(p, "action", null);
        if (action == null) throw new IllegalArgumentException("camera.action is required (path, orbit, follow, attach or shot)");
        return switch (action) {
            case "path" -> path(p);
            case "orbit" -> orbit(p, current);
            case "follow", "attach" -> follow(p, action.equals("attach"));
            case "shot" -> shot(p, current);
            default -> throw new IllegalArgumentException("a camera motion is path, orbit, follow, attach or shot, not \"" + action + "\"");
        };
    }

    private static CameraFollow follow(JsonObject p, boolean attach) {
        Double fov = p.has("fov") ? p.get("fov").getAsDouble() : null;
        if (fov != null && (fov < 1 || fov > 170)) throw new IllegalArgumentException("fov must be 1-170 degrees");
        long duration = p.has("durationMs") ? Math.round(p.get("durationMs").getAsDouble()) : 0;
        return new CameraFollow(new CameraFollow.Settings(attach ? CameraFollow.Mode.ATTACH : CameraFollow.Mode.FOLLOW,
            str(p, "entity", "@self"),
            num(p, "distance", 6), num(p, "height", 2), num(p, "angle", 180), !"world".equals(str(p, "relative_to", "entity")),
            num(p, "smoothingMs", 250),
            num(p, "forward", -4), num(p, "up", 1), num(p, "right", 0),
            (float) num(p, "yawOffset", 0), (float) num(p, "pitchOffset", 0), duration, fov));
    }

    private static CameraPath shot(JsonObject p, CameraOverride.Pose current) {
        String shot = str(p, "shot", null);
        if (shot == null) throw new IllegalArgumentException("shot needs shot: push_in, pull_out, crane_up, crane_down, reveal or dolly_zoom");
        if (!p.has("target") || !p.get("target").isJsonObject()) throw new IllegalArgumentException("shot needs target {x,y,z} (to follow an entity use action follow)");
        Vec target = vec(p.getAsJsonObject("target"), "target");
        if (!p.has("durationMs")) throw new IllegalArgumentException("shot needs durationMs");
        // validating without a world: any pose a few blocks from the target will do
        CameraOverride.Pose from = current != null ? current : new CameraOverride.Pose(target.x(), target.y() + 2, target.z() - 8, 0, 10);
        return CameraShots.build(shot, from, target, Math.round(p.get("durationMs").getAsDouble()), num(p, "amount", 0),
            num(p, "fov", 70), p.has("endFov") ? p.get("endFov").getAsDouble() : null);
    }

    private static double num(JsonObject p, String key, double fallback) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsDouble() : fallback;
    }

    private static CameraPath path(JsonObject p) {
        if (!p.has("keyframes") || !p.get("keyframes").isJsonArray()) throw new IllegalArgumentException("path needs keyframes [{t,x,y,z,yaw,pitch}]");
        List<CameraPath.Keyframe> keys = new ArrayList<>();
        int i = 0;
        for (JsonElement e : p.getAsJsonArray("keyframes")) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("keyframe " + i + " is not an object");
            JsonObject k = e.getAsJsonObject();
            for (String f : new String[] {"t", "x", "y", "z"}) {
                if (!k.has(f)) throw new IllegalArgumentException("keyframe " + i + " needs " + f);
            }
            keys.add(new CameraPath.Keyframe(k.get("t").getAsDouble(), k.get("x").getAsDouble(), k.get("y").getAsDouble(), k.get("z").getAsDouble(),
                    k.has("yaw") ? k.get("yaw").getAsFloat() : null,
                    k.has("pitch") ? k.get("pitch").getAsFloat() : null,
                    k.has("fov") ? k.get("fov").getAsDouble() : null,
                    k.has("roll") ? k.get("roll").getAsDouble() : null));
            i++;
        }
        boolean smooth = !"linear".equals(str(p, "interpolation", "smooth"));
        CameraPath.Ease ease = CameraPath.Ease.parse(str(p, "ease", "inOut"));
        Vec lookAt = p.has("lookAt") && p.get("lookAt").isJsonObject() ? vec(p.getAsJsonObject("lookAt"), "lookAt") : null;
        return CameraPath.of(keys, smooth, ease, lookAt);
    }

    private static CameraOrbit orbit(JsonObject p, CameraOverride.Pose current) {
        if (!p.has("center") || !p.get("center").isJsonObject()) throw new IllegalArgumentException("orbit needs center {x,y,z}");
        Vec center = vec(p.getAsJsonObject("center"), "center");
        if (!p.has("radius")) throw new IllegalArgumentException("orbit needs radius");
        if (!p.has("durationMs")) throw new IllegalArgumentException("orbit needs durationMs");
        double radius = p.get("radius").getAsDouble();
        long duration = Math.round(p.get("durationMs").getAsDouble());
        double height = p.has("height") ? p.get("height").getAsDouble() : radius / 2;
        double start = p.has("startAngle") ? p.get("startAngle").getAsDouble()
                : current == null ? 0 : CameraOrbit.angleOf(center, current.x(), current.z());
        double degrees = p.has("degrees") ? p.get("degrees").getAsDouble() : 360.0;
        boolean ease = "inOut".equals(str(p, "ease", "none"));
        return CameraOrbit.of(center, radius, height, start, degrees, duration, ease);
    }

    public static Vec vec(JsonObject o, String name) {
        if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) throw new IllegalArgumentException(name + " needs x, y and z");
        return new Vec(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    private static String str(JsonObject p, String key, String fallback) {
        return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsString() : fallback;
    }
}
