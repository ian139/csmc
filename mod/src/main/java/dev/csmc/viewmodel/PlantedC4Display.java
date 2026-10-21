package dev.csmc.viewmodel;

/**
 * Pure state for the planted C4's approximated display: a M:SS countdown readout rounded up to whole seconds, and an
 * LED that flashes briefly after each server beep. The LED window is shorter than the fastest beep gap, so it still
 * blinks in the final moments.
 */
public final class PlantedC4Display {
    /** Ticks the LED stays lit after a beep. */
    public static final float LED_TICKS = 1.5F;
    /** Longest readout: the documented 40-second fuse; longer fuses clamp to it. */
    static final int MAX_SECONDS = 40;
    private static final String[] COUNTDOWN = new String[MAX_SECONDS + 1];

    static {
        for (int seconds = 0; seconds < COUNTDOWN.length; seconds++) {
            COUNTDOWN[seconds] = seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }
    }

    private PlantedC4Display() {}

    /** Whole seconds left, rounded up so the readout reaches 0:00 only when the fuse does. */
    public static int secondsLeft(int fuseTicks) {
        return fuseTicks <= 0 ? 0 : fuseTicks / 20 + (fuseTicks % 20 == 0 ? 0 : 1);
    }

    /** Cached readout for the remaining fuse; never allocates. */
    public static String countdown(int fuseTicks) {
        return COUNTDOWN[Math.min(secondsLeft(fuseTicks), COUNTDOWN.length - 1)];
    }

    /** LED state {@code ticksSinceBeep} (partial ticks included) after the latest beep; negative means none heard. */
    public static boolean ledLit(float ticksSinceBeep) {
        return ticksSinceBeep >= 0 && ticksSinceBeep < LED_TICKS;
    }
}
