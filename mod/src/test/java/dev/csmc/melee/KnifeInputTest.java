package dev.csmc.melee;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class KnifeInputTest {
    private final byte[] out = new byte[KnifeInput.CAPACITY];

    private byte[] tick(KnifeInput input, boolean holding, boolean focused, boolean attack, boolean use) {
        return Arrays.copyOf(out, input.tick(holding, focused, attack, use, out));
    }

    @Test
    public void spectatorsKeepVanillaInput() {
        assertTrue(KnifeInput.ownsInput(true, false));
        assertFalse(KnifeInput.ownsInput(true, true));
        assertFalse(KnifeInput.ownsInput(false, false));
    }

    @Test
    public void attackSlashesAndUseStabs() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.ATTACK, true);
        assertArrayEquals(new byte[]{KnifeInput.SLASH}, tick(input, true, true, true, false));
        input.edge(KnifeInput.ATTACK, false);
        input.edge(KnifeInput.USE, true);
        assertArrayEquals(new byte[]{KnifeInput.STAB}, tick(input, true, true, false, true));
    }

    @Test
    public void holdingDoesNotRepeat() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.ATTACK, true);
        assertEquals(1, tick(input, true, true, true, false).length);
        input.edge(KnifeInput.ATTACK, true); // key repeat / duplicate setDown
        assertEquals(0, tick(input, true, true, true, false).length);
        assertEquals(0, tick(input, true, true, true, false).length);
    }

    @Test
    public void clicksBetweenTicksKeepArrivalOrder() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.USE, true);
        input.edge(KnifeInput.USE, false);
        input.edge(KnifeInput.ATTACK, true);
        input.edge(KnifeInput.ATTACK, false);
        input.edge(KnifeInput.ATTACK, true);
        input.edge(KnifeInput.ATTACK, false);
        assertArrayEquals(new byte[]{KnifeInput.STAB, KnifeInput.SLASH, KnifeInput.SLASH},
                tick(input, true, true, false, false));
    }

    @Test
    public void pressesWithoutKnifeOrFocusAreDropped() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.ATTACK, true);
        assertEquals(0, tick(input, false, true, true, false).length);
        input.edge(KnifeInput.ATTACK, false);
        input.edge(KnifeInput.ATTACK, true);
        assertEquals(0, tick(input, true, false, true, false).length);
        // Nothing stale fires once the knife is usable again.
        assertEquals(0, tick(input, true, true, true, false).length);
    }

    @Test
    public void pollReconcilesMissedRelease() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.ATTACK, true);
        assertEquals(1, tick(input, true, true, true, false).length);
        // Vanilla released the key (e.g. focus loss) without an edge; the poll sees it up.
        tick(input, true, true, false, false);
        input.edge(KnifeInput.ATTACK, true);
        assertArrayEquals(new byte[]{KnifeInput.SLASH}, tick(input, true, true, true, false));
    }

    @Test
    public void queueIsBounded() {
        KnifeInput input = new KnifeInput();
        for (int i = 0; i < KnifeInput.CAPACITY * 3; i++) {
            input.edge(KnifeInput.ATTACK, true);
            input.edge(KnifeInput.ATTACK, false);
        }
        assertEquals(KnifeInput.CAPACITY, tick(input, true, true, false, false).length);
        assertEquals(0, tick(input, true, true, false, false).length);
    }

    @Test
    public void resetClearsQueueAndState() {
        KnifeInput input = new KnifeInput();
        input.edge(KnifeInput.USE, true);
        input.reset();
        assertEquals(0, tick(input, true, true, false, false).length);
        input.edge(KnifeInput.USE, true);
        assertEquals(1, tick(input, true, true, false, true).length);
    }
}
