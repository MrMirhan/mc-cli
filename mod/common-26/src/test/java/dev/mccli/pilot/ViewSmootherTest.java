package dev.mccli.pilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewSmootherTest {
    @Test
    void nothingToApplyBeforeThePilotTurnsTheHead() {
        assertNull(new ViewSmoother().at(0.5f));
    }

    @Test
    void everyFrameBetweenTwoTicksShowsItsShareOfTheTurn() {
        ViewSmoother s = new ViewSmoother();
        s.tick(10, 0, 16, 3);
        assertEquals(10, s.at(0f)[0], 1e-4);
        assertEquals(12, s.at(1f / 3)[0], 1e-4);
        assertEquals(14, s.at(2f / 3)[0], 1e-4);
        assertEquals(2, s.at(2f / 3)[1], 1e-4);
        assertEquals(16, s.at(1f)[0], 1e-4);
    }

    @Test
    void aTurnAcrossBehindTakesTheShortWay() {
        ViewSmoother s = new ViewSmoother();
        s.tick(170, 0, -170, 0);
        assertEquals(180, Math.abs(s.at(0.5f)[0]), 1e-4);
    }

    @Test
    void clearGivesTheCameraBackToThePlayer() {
        ViewSmoother s = new ViewSmoother();
        s.tick(0, 0, 5, 0);
        s.clear();
        assertNull(s.at(0.5f));
    }
}
