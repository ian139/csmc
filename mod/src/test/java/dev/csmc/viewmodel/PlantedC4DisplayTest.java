package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlantedC4DisplayTest {
    @Test
    public void countdownRoundsUpToWholeSeconds() {
        assertEquals("0:40", PlantedC4Display.countdown(800));
        assertEquals("0:40", PlantedC4Display.countdown(781));
        assertEquals("0:39", PlantedC4Display.countdown(780));
        assertEquals("0:09", PlantedC4Display.countdown(180));
        assertEquals("0:01", PlantedC4Display.countdown(1));
        assertEquals("0:00", PlantedC4Display.countdown(0));
        assertEquals("0:00", PlantedC4Display.countdown(-5));
    }

    @Test
    public void countdownIsCachedAndClampedAboveTheFuse() {
        assertSame(PlantedC4Display.countdown(800), PlantedC4Display.countdown(790));
        assertEquals("0:40", PlantedC4Display.countdown(10_000));
        assertEquals("0:40", PlantedC4Display.countdown(Integer.MAX_VALUE));
        assertEquals(107374183, PlantedC4Display.secondsLeft(Integer.MAX_VALUE));
    }

    @Test
    public void ledFlashesAfterABeepAndStillBlinksAtTheFastestGap() {
        assertFalse(PlantedC4Display.ledLit(-1));
        assertTrue(PlantedC4Display.ledLit(0));
        assertTrue(PlantedC4Display.ledLit(1.4F));
        assertFalse(PlantedC4Display.ledLit(1.5F));
        // Fastest server beep gap is 2 ticks: the LED must go dark between beeps.
        assertFalse(PlantedC4Display.ledLit(1.9F));
    }
}
