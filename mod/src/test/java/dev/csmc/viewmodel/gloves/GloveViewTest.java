package dev.csmc.viewmodel.gloves;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class GloveViewTest {
    @Test
    public void bareArmKeepsItsSkinAndGlovesTakeTheFinish() {
        assertFalse(GloveView.paintable("bare_arm_133_color_psd_5b76e0cf.vtex"));
        assertTrue(GloveView.paintable("glove_sporty_left_color.png"));
        assertTrue(GloveView.paintable(null));
    }

    @Test
    public void onlyTheDefaultGlovesMeshIsReplaced() {
        assertTrue(GloveView.replacedByGlove("arms_gloves"));
        assertFalse(GloveView.replacedByGlove("sleeves"));
        assertFalse(GloveView.replacedByGlove(null));
    }

    @Test
    public void creativeTabMovesOnlyTheGloveSlotOffTheHotbar() {
        assertEquals(GloveView.CREATIVE_X, GloveView.creativeX(46, 46, 27));
        assertEquals(GloveView.CREATIVE_Y, GloveView.creativeY(46, 46, 112));
        assertEquals(27, GloveView.creativeX(37, 46, 27));
        assertEquals(112, GloveView.creativeY(37, 46, 112));
        // Clear of the offhand slot (35, 20) and its 18 px frame.
        assertTrue(GloveView.CREATIVE_X + 17 < 35 - 1);
    }

    @Test
    public void armsFallBackToDefaultGlovesWithoutUsableAssets() {
        Set<String> cached = Set.of("sporty_gloves");
        assertEquals("sporty_gloves", GloveView.armsGlove("sporty_gloves", cached::contains));
        assertNull(GloveView.armsGlove("specialist_gloves", cached::contains));
        assertNull(GloveView.armsGlove(null, cached::contains));
    }

    @Test
    public void residencyIsBoundedAndDisposesEvictedReplacedAndCleared() {
        List<String> disposed = new ArrayList<>();
        BoundedResidency<String> residency = new BoundedResidency<>(2, disposed::add);
        residency.put("a", "A");
        residency.put("b", "B");
        residency.get("a"); // a is now most recent
        residency.put("c", "C");
        assertEquals(List.of("B"), disposed);
        assertEquals(2, residency.size());
        residency.put("a", "A2");
        assertEquals(List.of("B", "A"), disposed);
        residency.clear();
        assertEquals(4, disposed.size());
        assertTrue(disposed.containsAll(List.of("A2", "C")));
        assertEquals(0, residency.size());
    }
}
