package dev.csmc.melee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class KnifeClipChooserTest {
    private static final Map<String, String> BUTTERFLY = Map.of("draw", "draw_butterfly",
            "slash", "light_miss1_butterfly", "slash_alt", "light_miss2_butterfly",
            "slash_hit", "light_hit1_butterfly", "slash_backstab", "light_backstab_butterfly",
            "stab", "heavy_miss1_butterfly", "stab_hit", "heavy_hit1_butterfly",
            "backstab", "heavy_backstab_butterfly", "inspect", "lookat01_butterfly", "idle", "idle1_butterfly");
    private static final List<String> BUTTERFLY_CLIPS = List.of("draw2_butterfly", "draw_butterfly",
            "heavy_backstab_butterfly", "heavy_hit1_butterfly", "heavy_miss1_butterfly", "idle1_butterfly",
            "idle2_butterfly", "light_backstab2_butterfly", "light_backstab_butterfly", "light_hit1_butterfly",
            "light_hit2_butterfly", "light_miss1_butterfly", "light_miss2_butterfly", "lookat01_butterfly",
            "lookat02_butterfly", "lookat03_butterfly");

    private final KnifeClipChooser butterfly = new KnifeClipChooser(BUTTERFLY, BUTTERFLY_CLIPS);

    @Test
    public void variantsShareFamilyAndSuffix() {
        assertEquals(List.of("draw2_butterfly", "draw_butterfly"), butterfly.variants("draw"));
        assertEquals(List.of("lookat01_butterfly", "lookat02_butterfly", "lookat03_butterfly"),
                butterfly.variants("inspect"));
        assertEquals(List.of("light_miss1_butterfly", "light_miss2_butterfly"), butterfly.variants("slash"));
        assertEquals(List.of("light_hit1_butterfly", "light_hit2_butterfly"), butterfly.variants("slash_hit"));
        assertEquals(List.of("heavy_backstab_butterfly"), butterfly.variants("backstab"));
        assertEquals(List.of("idle1_butterfly", "idle2_butterfly"), butterfly.variants("idle"));
        assertTrue(butterfly.variants("missing").isEmpty());
    }

    @Test
    public void flipDrawIncludesLookatDraw() {
        KnifeClipChooser flip = new KnifeClipChooser(Map.of("draw", "draw_flip", "inspect", "lookat01_flip"),
                List.of("draw_flip", "lookat01_draw_flip", "lookat01_flip"));
        assertEquals(List.of("draw_flip", "lookat01_draw_flip"), flip.variants("draw"));
        assertEquals(List.of("lookat01_flip"), flip.variants("inspect"));
    }

    @Test
    public void pushHasFiveLightSwings() {
        KnifeClipChooser push = new KnifeClipChooser(Map.of("slash", "light_miss1_push"), List.of("light_miss1_push",
                "light_miss2_push", "light_miss3_push", "light_miss4_push", "light_miss5_push", "all_light1_anims_push"));
        assertEquals(5, push.variants("slash").size());
    }

    @Test
    public void consecutivePicksNeverRepeatAndCoverVariants() {
        Random random = new Random(7);
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 50; i++) {
            String pick = butterfly.pick("inspect", random);
            assertNotEquals(previous, pick);
            seen.add(pick);
            previous = pick;
        }
        assertEquals(3, seen.size());
        String a = butterfly.pick("slash", random);
        String b = butterfly.pick("slash", random);
        assertNotEquals(a, b);
        assertEquals("heavy_backstab_butterfly", butterfly.pick("backstab", random));
        assertNull(butterfly.pick("missing", random));
    }

    @Test
    public void idlesAlternate() {
        assertEquals("idle1_butterfly", butterfly.idle(0));
        assertEquals("idle2_butterfly", butterfly.idle(1));
        assertEquals("idle1_butterfly", butterfly.idle(2));
    }

    @Test
    public void mappedClipWithoutFamilyStillPlays() {
        KnifeClipChooser odd = new KnifeClipChooser(Map.of("slash", "swing_x"), List.of("swing_x"));
        assertEquals(List.of("swing_x"), odd.variants("slash"));
    }

    @Test
    public void hitAndBaseActions() {
        assertEquals("slash_hit", KnifeClipChooser.hitAction("slash"));
        assertEquals("stab_hit", KnifeClipChooser.hitAction("stab"));
        assertEquals("backstab", KnifeClipChooser.hitAction("backstab"));
        assertEquals("slash", KnifeClipChooser.baseAction("slash_backstab"));
        assertEquals("stab", KnifeClipChooser.baseAction("stab_hit"));
        assertEquals("draw", KnifeClipChooser.baseAction("draw"));
    }
}
