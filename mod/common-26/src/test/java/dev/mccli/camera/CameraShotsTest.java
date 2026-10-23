package dev.mccli.camera;

import dev.mccli.camera.CameraMath.Vec;
import dev.mccli.camera.CameraOverride.Pose;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CameraShotsTest {
    private static final Vec TARGET = new Vec(0, 64, 0);
    private static final Pose FROM = new Pose(0, 66, -10, 0, 10);

    @Test
    void pushInEndsCloserAndKeepsAiming() {
        CameraPath p = CameraShots.build("push_in", FROM, TARGET, 2000, 0, 70, null);
        Pose end = p.at(2000);
        double d = CameraShots.dist(new Vec(end.x(), end.y(), end.z()), TARGET);
        assertEquals(CameraShots.dist(new Vec(FROM.x(), FROM.y(), FROM.z()), TARGET) * 0.6, d, 1e-6);
        assertEquals(0, end.yaw(), 0.01);
    }

    @Test
    void craneUpRises() {
        Pose end = CameraShots.build("crane_up", FROM, TARGET, 1000, 4, 70, null).at(1000);
        assertEquals(70, end.y(), 1e-6);
        assertTrue(end.pitch() > 0);
    }

    @Test
    void dollyZoomKeepsTheSubjectWidth() {
        CameraPath p = CameraShots.build("dolly_zoom", FROM, TARGET, 3000, 0, 70, 30.0);
        double w0 = width(p, 0), w1 = width(p, 3000), wMid = width(p, 1500);
        assertEquals(w0, w1, 1e-3);
        assertEquals(w0, wMid, 0.05);
        assertEquals(70, p.fovAt(0), 1e-9);
        assertEquals(30, p.fovAt(3000), 1e-9);
    }

    private static double width(CameraPath p, double ms) {
        Pose pose = p.at(ms);
        return 2 * CameraShots.dist(new Vec(pose.x(), pose.y(), pose.z()), TARGET) * Math.tan(Math.toRadians(p.fovAt(ms)) / 2);
    }

    @Test
    void unknownShotIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CameraShots.build("zoom", FROM, TARGET, 1000, 0, 70, null));
    }

    @Test
    void aroundBehindIsOppositeTheHeading() {
        // heading south (+z): behind is north (-z)
        Vec v = CameraShots.around(TARGET, 0, 6, 2, 180);
        assertEquals(-6, v.z(), 1e-9);
        assertEquals(66, v.y(), 1e-9);
        assertEquals(0, v.x(), 1e-9);
    }

    @Test
    void attachedBehindAndAboveTheEye() {
        Vec v = CameraShots.attached(TARGET, 0, 0, -4, 1, 0);
        assertEquals(-4, v.z(), 1e-9);
        assertEquals(65, v.y(), 1e-9);
        Vec right = CameraShots.attached(TARGET, 0, 0, 0, 0, 2);
        assertEquals(-2, right.x(), 1e-9); // facing south, the right hand is west
    }

    @Test
    void smoothingLagsThenArrives() {
        Vec a = new Vec(0, 0, 0), b = new Vec(10, 0, 0);
        assertEquals(10 * (1 - Math.exp(-1)), CameraShots.smooth(a, b, 250, 250).x(), 1e-9);
        assertEquals(b, CameraShots.smooth(a, b, 16, 0));
        assertEquals(b, CameraShots.smooth(null, b, 16, 250));
    }

    @Test
    void pathFovMustBeOnAllKeyframes() {
        assertThrows(IllegalArgumentException.class, () -> CameraPath.of(List.of(
            new CameraPath.Keyframe(0, 0, 0, 0, 0f, 0f, 70.0), new CameraPath.Keyframe(1000, 1, 0, 0, 0f, 0f, null)), false, false, null));
        CameraPath ok = CameraPath.of(List.of(
            new CameraPath.Keyframe(0, 0, 0, 0, 0f, 0f, 70.0), new CameraPath.Keyframe(1000, 1, 0, 0, 0f, 0f, 30.0)), false, false, null);
        assertEquals(50, ok.fovAt(500), 1e-9);
        assertNull(CameraPath.of(List.of(new CameraPath.Keyframe(0, 0, 0, 0, 0f, 0f), new CameraPath.Keyframe(10, 1, 0, 0, 0f, 0f)), false, false, null).fovAt(5));
    }
}
