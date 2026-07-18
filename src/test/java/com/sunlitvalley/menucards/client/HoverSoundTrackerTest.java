package com.sunlitvalley.menucards.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoverSoundTrackerTest {
    @Test
    void playsOnlyWhenEnteringANewCard() {
        HoverSoundTracker tracker = new HoverSoundTracker();

        assertFalse(tracker.update(-1));
        assertTrue(tracker.update(0));
        assertFalse(tracker.update(0));
        assertTrue(tracker.update(1));
        assertFalse(tracker.update(-1));
        assertTrue(tracker.update(1));
    }
}
