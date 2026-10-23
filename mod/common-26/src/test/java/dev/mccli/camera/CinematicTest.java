package dev.mccli.camera;

import dev.mccli.camera.CameraMath.Vec;
import dev.mccli.camera.CameraOverride.Pose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CinematicTest {
    private static final Vec TARGET = new Vec(0, 64, 0);
    private static final Pose FROM = new Pose(0, 70, -20, 0, 15);

    @AfterEach
    void wallClock() {
        CameraOverride.INSTANCE.clear();
        CameraOverride.INSTANCE.useRenderClock(null);
    }

    @Test
    void rollIsKeyframedLikeFov() {
        CameraPath p = CameraPath.of(List.of(
            new CameraPath.Keyframe(0, 0, 0, 0, 0f, 0f, null, 0.0),
            new CameraPath.Keyframe(1000, 1, 0, 0, 0f, 0f, null, 20.0)), false, CameraPath.Ease.NONE, null);
        assertEquals(10, p.rollAt(500), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> CameraPath.of(List.of(
            new CameraPath.Keyframe(0, 0, 0, 0, 0f, 0f, null, 5.0),
            new CameraPath.Keyframe(1000, 1, 0, 0, 0f, 0f, null, null)), false, CameraPath.Ease.NONE, null));
    }

    @Test
    void easesStartAndEndStill() {
        for (CameraPath.Ease e : CameraPath.Ease.values()) {
            assertEquals(0, e.apply(0), 1e-12, e.name());
            assertEquals(1, e.apply(1), 1e-12, e.name());
        }
        assertTrue(CameraPath.Ease.SMOOTHER.apply(0.1) < CameraPath.Ease.IN_OUT.apply(0.1));
        assertTrue(CameraPath.Ease.IN.apply(0.5) < 0.5 && CameraPath.Ease.OUT.apply(0.5) > 0.5);
        assertEquals(CameraPath.Ease.SMOOTHER, CameraPath.Ease.parse("smoother"));
        assertEquals(CameraPath.Ease.IN_OUT, CameraPath.Ease.parse("inOut"));
    }

    @Test
    void theRenderClockMovesOnlyWhenAFrameIsTaken() {
        CameraPath p = CameraShots.build("push_in", FROM, TARGET, 1000, 0, 70, null);
        CameraOverride.INSTANCE.useRenderClock(0L);
        CameraOverride.INSTANCE.play(p, 0);
        Pose a = CameraOverride.INSTANCE.get();
        Pose again = CameraOverride.INSTANCE.get();
        assertEquals(a, again, "wall time must not move the camera in render mode");
        CameraOverride.INSTANCE.useRenderClock(500_000_000L);
        Pose half = CameraOverride.INSTANCE.get();
        assertNotEquals(a.z(), half.z());
        assertEquals(p.at(500).z(), half.z(), 1e-9);
    }

    @Test
    void fpvDiveBanksAndEndsLowPastTheTarget() {
        CameraPath p = CameraShots.build("fpv_dive", FROM, TARGET, 4000, 0, 70, null);
        Pose end = p.at(4000);
        assertTrue(end.y() < FROM.y(), "ends lower");
        assertTrue(end.z() > TARGET.z(), "passes the target");
        double maxRoll = 0;
        for (int ms = 0; ms <= 4000; ms += 100) maxRoll = Math.max(maxRoll, Math.abs(p.rollAt(ms)));
        assertTrue(maxRoll > 5, "banks into the dive: " + maxRoll);
        assertEquals(0, p.rollAt(4000), 1e-6);
    }

    @Test
    void parallaxKeepsTheHeadingAndSlidesSideways() {
        CameraPath p = CameraShots.build("parallax", FROM, TARGET, 3000, 6, 70, null);
        Pose a = p.at(0), b = p.at(3000);
        assertEquals(a.yaw(), b.yaw(), 1e-6);
        assertEquals(6, Math.abs(b.x() - a.x()), 1e-6);
        assertEquals(a.z(), b.z(), 1e-6);
    }

    @Test
    void orbitRiseClimbsWhileCircling() {
        CameraPath p = CameraShots.build("orbit_rise", FROM, TARGET, 6000, 0, 70, null);
        Pose a = p.at(0), b = p.at(6000);
        assertTrue(b.y() > a.y() + 3);
        double ra = Math.hypot(a.x() - TARGET.x(), a.z() - TARGET.z()), rb = Math.hypot(b.x() - TARGET.x(), b.z() - TARGET.z());
        assertEquals(ra, rb, 1.0);
    }

    @Test
    void flyoverPassesHighOverTheTarget() {
        CameraPath p = CameraShots.build("flyover", FROM, TARGET, 5000, 0, 70, null);
        Pose mid = p.at(2500);
        assertTrue(mid.y() > TARGET.y() + 8);
        assertTrue(Math.hypot(mid.x() - TARGET.x(), mid.z() - TARGET.z()) < 6);
        assertTrue(mid.pitch() > 45, "looks down over the target: " + mid.pitch());
    }
}
