package dev.mccli.pilot;

import dev.mccli.pilot.PilotBrain.Act;
import dev.mccli.pilot.PilotBrain.Mode;
import dev.mccli.pilot.PilotBrain.Sense;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PilotBrainTest {
    private static Sense at(long now, double tx, double tz, double health) {
        return new Sense(now, 0, 64, 65.62, 0, 0, 0, health, true, false, tx, 64.0, tz);
    }

    /** Runs a fight for {@code ms} with the foe standing still at (0, z). */
    private static int[] fight(String style, String profile, double z, long ms) {
        PilotBrain b = new PilotBrain(Mode.FIGHT, profile, style, 0, 0, "seed");
        int attacks = 0, forward = 0;
        for (long t = 0; t <= ms; t += 50) {
            Act a = b.tick(at(t, 0, z, 20));
            if (a.attack()) attacks++;
            if (a.forward()) forward++;
        }
        return new int[] {attacks, forward};
    }

    @Test
    void oneEightSpamsInsideTheProfileCpsBand() {
        int attacks = fight("1.8", "mid", 2.5, 10_000)[0];
        // mid: 8-11 CPS, 12% misses, a reaction delay first, one decision per 50 ms tick
        assertTrue(attacks >= 45 && attacks <= 110, "attacks in 10 s: " + attacks);
    }

    @Test
    void oneNineWaitsForTheCooldown() {
        int attacks = fight("1.9", "mid", 2.5, 10_000)[0];
        assertTrue(attacks >= 8 && attacks <= 16, "attacks in 10 s: " + attacks);
    }

    @Test
    void outOfReachItClosesInAndDoesNotSwing() {
        int[] r = fight("1.8", "tryhard", 8, 3000);
        assertEquals(0, r[0]);
        assertTrue(r[1] > 20, "should walk forward: " + r[1]);
    }

    @Test
    void nothingHappensBeforeTheReaction() {
        PilotBrain b = new PilotBrain(Mode.FIGHT, "rookie", "1.8", 0, 0, "seed");
        Act first = b.tick(at(0, 0, 2.5, 20));
        Act early = b.tick(at(50, 0, 2.5, 20));
        assertFalse(first.attack() || early.attack() || early.forward());
    }

    @Test
    void lowHealthBacksOff() {
        PilotBrain b = new PilotBrain(Mode.FIGHT, "tryhard", "1.8", 0, 0, "seed");
        boolean back = false;
        for (long t = 0; t <= 2000; t += 50) back |= b.tick(at(t, 0, 2.5, 4)).back();
        assertTrue(back);
    }

    @Test
    void theHeadTurnsLikeAPersonNotASnap() {
        PilotBrain b = new PilotBrain(Mode.LOOK, "mid", "1.8", 0, 0, "seed");
        // target straight east: yaw -90
        double first = b.tick(new Sense(0, 0, 64, 65.6, 0, 0, 0, 20, true, false, 10.0, 65.6, 0.0)).yaw();
        double after50 = b.tick(new Sense(50, 0, 64, 65.6, 0, 0, 0, 20, true, false, 10.0, 65.6, 0.0)).yaw();
        double end = after50;
        for (long t = 100; t <= 2000; t += 50) end = b.tick(new Sense(t, 0, 64, 65.6, 0, 0, 0, 20, true, false, 10.0, 65.6, 0.0)).yaw();
        assertTrue(Math.abs(after50 - first) < 30, "no snap in one tick: " + (after50 - first));
        assertEquals(-90, end, 2);
    }

    @Test
    void walkingStopsAtThePointAndJumpsWhenBlocked() {
        PilotBrain b = new PilotBrain(Mode.WALK, "mid", "1.8", 0, 0, "seed");
        boolean jumped = false;
        Act a = null;
        for (long t = 0; t <= 1500; t += 50) {
            a = b.tick(new Sense(t, 0, 64, 65.6, 0, 0, 0, 20, true, true, 0.0, 64.0, 10.0));
            jumped |= a.jump();
        }
        assertTrue(a.forward() && jumped);
        assertTrue(b.tick(new Sense(1600, 0, 64, 65.6, 9.8, 0, 0, 20, true, false, 0.0, 64.0, 10.0)).done());
    }

    @Test
    void stopsWhenTimeIsUpAndRejectsUnknownStyles() {
        PilotBrain b = new PilotBrain(Mode.FIGHT, "mid", "1.8", 0, 500, "seed");
        Act a = null;
        for (long t = 0; t <= 600; t += 50) a = b.tick(at(t, 0, 2.5, 20));
        assertTrue(a.done() && !a.attack());
        assertThrows(IllegalArgumentException.class, () -> new PilotBrain(Mode.FIGHT, "mid", "1.7", 0, 0, "s"));
        assertThrows(IllegalArgumentException.class, () -> new PilotBrain(Mode.FIGHT, "god", "1.8", 0, 0, "s"));
    }
}
