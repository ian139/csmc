package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkinTextureBudgetTest {
    @Test
    public void shineMasksAreCountedInThePoolBudget() {
        assertEquals(16L << 20, SkinTextureBudget.shineMaskBytes());
        assertEquals((128L + 24L + 16L) << 20, SkinTextureBudget.totalBytes());
        assertTrue(SkinTextureBudget.totalBytes() <= SkinTextureBudget.MAX_BYTES);
    }

    @Test
    public void masksStaySmallBesideComposites() {
        assertTrue(SkinTextureBudget.SHINE_MASK_EDGE <= 512);
        assertTrue(SkinTextureBudget.shineMaskBytes() * 8
                <= SkinTextureBudget.bytes(SkinTextureBudget.FULL_VARIANTS, SkinResolution.FULL.edge));
    }
}
