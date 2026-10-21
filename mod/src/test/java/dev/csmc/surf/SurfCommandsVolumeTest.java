package dev.csmc.surf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SurfCommandsVolumeTest {
    @Test
    public void hugeBoxThatWrapsLongIsRejected() {
        // 58,000,000 * 4064 * 58,000,000 = 13,671,296,000,000,000,000 wraps to a negative long.
        assertTrue((long) 58_000_000 * 4064 * 58_000_000 < 0);
        assertTrue(SurfCommands.exceedsMaxVolume(58_000_000, 4064, 58_000_000));
        assertEquals("13671296000000000000", SurfCommands.exactVolume(58_000_000, 4064, 58_000_000).toString());
    }

    @Test
    public void limitIsInclusive() {
        assertFalse(SurfCommands.exceedsMaxVolume(32, 32, 32));
        assertTrue(SurfCommands.exceedsMaxVolume(32, 32, 33));
        assertFalse(SurfCommands.exceedsMaxVolume(32768, 1, 1));
        assertTrue(SurfCommands.exceedsMaxVolume(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
